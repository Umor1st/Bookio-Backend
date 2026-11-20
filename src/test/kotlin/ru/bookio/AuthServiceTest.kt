package ru.bookio

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import ru.bookio.bootstrap.configureServer
import ru.bookio.presentation.dto.ErrorBody
import ru.bookio.presentation.dto.ErrorResponse
import ru.bookio.presentation.routes.AuthRateLimiter
import kotlinx.serialization.json.*
import ru.bookio.infrastructure.security.*
import kotlinx.coroutines.runBlocking
import java.time.*
import java.util.UUID
import kotlin.test.*
import ru.bookio.application.auth.AuthService
import ru.bookio.domain.error.*
import ru.bookio.domain.model.*
import ru.bookio.domain.repository.AuthRepository
import ru.bookio.domain.service.*

private class MemoryRepository : AuthRepository {
    val users = mutableMapOf<String, User>()
    val sessions = mutableMapOf<String, Pair<Session, Instant?>>()
    override fun <T> transaction(block: () -> T): T = synchronized(this) { block() }
    override fun findUser(login: String, lock: Boolean) = users[login]
    override fun saveUser(user: User, now: Instant, existing: Boolean) { users[user.login] = user }
    override fun revokeUserSessions(userId: UUID, now: Instant) { sessions.replaceAll { _, value -> if (value.first.userId == userId) value.first to now else value } }
    override fun createSession(session: Session) { sessions[session.tokenHash] = session to null }
    override fun findSessionUser(hash: String, now: Instant): User? = sessions[hash]?.takeIf { it.second == null && it.first.expiresAt > now }?.let { session -> users.values.single { it.id == session.first.userId } }
    override fun revokeSession(hash: String, now: Instant): Boolean { val s = sessions[hash] ?: return false; if (s.second != null || s.first.expiresAt <= now) return false; sessions[hash] = s.first to now; return true }
}
private class FakePasswords : PasswordHasher { override fun hash(password: String) = password; override fun verify(password: String, hash: String) = password == hash }
private class FakeTokens : Tokens { var n = 0; override fun generate() = (++n).toString().padStart(43, 'a'); override fun hash(token: String) = token }
class AuthServiceTest {
    private val repo = MemoryRepository()
    private val instant = Instant.parse("2026-01-01T00:00:00Z")
    private val service = AuthService(repo, FakePasswords(), FakeTokens(), Clock.fixed(instant, ZoneOffset.UTC))
    private fun input(password: String = "password") = Registration("User.Name", password, "  Ivanov ", " Ivan ", "  ", 1, " A ")
    private suspend fun error(code: AuthErrorCode, block: suspend () -> Unit) { assertEquals(code, assertFailsWith<AuthError> { block() }.code) }

    private suspend fun assertApiError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status)
        val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("error"), envelope.keys)
        val error = envelope.getValue("error").jsonObject
        assertEquals(setOf("code", "message", "details"), error.keys)
        assertEquals(code, error.getValue("code").jsonPrimitive.content)
        val message = error.getValue("message").jsonPrimitive.content
        assertTrue(message.isNotBlank())
        assertNotEquals(code, message)
        assertTrue(error.getValue("details").jsonObject.isEmpty())
    }
    @Test fun registrationAndRejectedReregistration() = runBlocking {
        val first = service.register(input()); assertEquals("user.name", first.user.login); assertEquals("Ivanov", first.user.lastName); assertNull(first.user.middleName)
        assertEquals(Role.STUDENT, first.user.role); assertEquals(AccountStatus.PENDING, first.user.status); assertEquals(instant.plus(Duration.ofDays(30)), first.expiresAt)
        error(AuthErrorCode.LOGIN_ALREADY_EXISTS) { service.register(input()) }
        repo.users[first.user.login] = first.user.copy(status = AccountStatus.REJECTED)
        val again = service.register(input("newpass1").copy(firstName = " New ")); assertEquals(first.user.id, again.user.id); assertEquals("New", again.user.firstName)
        error(AuthErrorCode.INVALID_SESSION) { service.me(first.token) }
        assertEquals(again.user.id, service.login("USER.NAME", "newpass1").user.id)
    }
    @Test fun loginAndSessions() = runBlocking {
        val first = service.register(input()); for (status in AccountStatus.entries) { repo.users[first.user.login] = first.user.copy(status = status); assertEquals(status, service.login("USER.NAME", "password").user.status) }
        error(AuthErrorCode.INVALID_CREDENTIALS) { service.login("unknown", "password") }; error(AuthErrorCode.INVALID_CREDENTIALS) { service.login("user.name", "badpass1") }
        val other = service.login("user.name", "password"); service.logout(first.token)
        error(AuthErrorCode.INVALID_SESSION) { service.logout(first.token) }; assertEquals(first.user.id, service.me(other.token).id)
        for (bad in listOf("x".repeat(43))) error(AuthErrorCode.INVALID_SESSION) { service.me(bad) }
        val expired = AuthService(repo, FakePasswords(), FakeTokens(), Clock.fixed(instant.plus(Duration.ofDays(30)), ZoneOffset.UTC)); error(AuthErrorCode.INVALID_SESSION) { expired.me(other.token) }
    }
    @Test fun validatesInput() = runBlocking {
        for (p in listOf("x".repeat(7), "x".repeat(65), "pass word", "password\u00e9", "password\n")) error(AuthErrorCode.INVALID_DATA) { service.register(input(p)) }
        for (p in listOf("x".repeat(8), "x".repeat(64))) { val r = MemoryRepository(); AuthService(r, FakePasswords(), FakeTokens(), Clock.fixed(instant, ZoneOffset.UTC)).register(input(p)) }
        for (bad in listOf(input().copy(grade = 0), input().copy(grade = 12), input().copy(lastName = "  "), input().copy(classLetter = "      "), input().copy(middleName = "x".repeat(101)))) error(AuthErrorCode.INVALID_DATA) { service.register(bad) }
    }
    @Test fun httpErrorsAndNullableFields() = testApplication {
        application { configureServer(service) }
        val bad = client.post("/api/v1/auth/register") { contentType(ContentType.Application.Json); setBody("{") }
        assertApiError(bad, HttpStatusCode.BadRequest, "INVALID_DATA")
        val registered = client.post("/api/v1/auth/register") { contentType(ContentType.Application.Json); setBody("""{"login":"User.Name","password":"password","lastName":"Ivanov","firstName":"Ivan","grade":1,"classLetter":"A"}""") }
        assertEquals(HttpStatusCode.Created, registered.status); assertTrue(registered.bodyAsText().contains("\"middleName\":null"))
        val unauthorized = client.get("/api/v1/auth/me")
        assertApiError(unauthorized, HttpStatusCode.Unauthorized, "INVALID_SESSION")
    }

    @Test fun authRequestsDistinguishUnsupportedMediaTypesFromInvalidJson() = testApplication {
        application { configureServer(service) }
        for (operation in listOf("register", "login")) {
            val unsupported = client.post("/api/v1/auth/$operation") {
                contentType(ContentType.Text.Plain)
                setBody("{}")
            }
            assertApiError(unsupported, HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE")
            val malformed = client.post("/api/v1/auth/$operation") {
                contentType(ContentType.Application.Json)
                setBody("{")
            }
            assertApiError(malformed, HttpStatusCode.BadRequest, "INVALID_DATA")
        }
    }

    @Test fun errorDetailsPreserveJsonTypes() {
        val details = Json.parseToJsonElement("""{"limit":30,"retryable":true,"context":{"field":null},"fields":["login"]}""").jsonObject
        val response = ErrorResponse(ErrorBody("RATE_LIMITED", "Too many requests.", details))
        assertEquals(details, Json.encodeToJsonElement(response).jsonObject.getValue("error").jsonObject.getValue("details"))
    }
    @Test fun securityImplementations() {
        val passwords = BCryptPasswordHasher(); val hash = passwords.hash("password")
        assertEquals(60, hash.length); assertTrue(hash.startsWith("$2a$12$")); assertTrue(passwords.verify("password", hash)); assertFalse(passwords.verify("Password", hash))
        assertTrue(passwords.verify("password", "\$2a\$10\$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG"))
        val tokens = SecureTokens(); val token = tokens.generate(); assertEquals(43, token.length); assertEquals(64, tokens.hash(token).length)
    }

    @Test fun httpContractAndRateLimit() = testApplication {
        val mutableClock = object : Clock() {
            var current = instant
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = current
        }
        val limiter = AuthRateLimiter(mutableClock, limit = 20)
        application { configureServer(service, limiter) }
        val body = """{"login":"User.Name","password":"password","lastName":"Ivanov","firstName":"Ivan","grade":1,"classLetter":"A"}"""
        suspend fun register() = client.post("/api/v1/auth/register") { contentType(ContentType.Application.Json); setBody(body) }
        suspend fun login(password: String = "password") = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"login":"USER.NAME","password":"$password"}""") }
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject
        val registered = register(); assertEquals(HttpStatusCode.Created, registered.status)
        val response = json(registered.bodyAsText()); val token = response.getValue("accessToken").jsonPrimitive.content
        val user = response.getValue("user").jsonObject
        assertEquals("STUDENT", user.getValue("role").jsonPrimitive.content); assertEquals("PENDING", user.getValue("status").jsonPrimitive.content)
        for (field in listOf("middleName", "grade", "classLetter")) assertTrue(user.containsKey(field))
        assertFalse(user.containsKey("password")); assertFalse(user.containsKey("passwordHash"))
        assertApiError(register(), HttpStatusCode.Conflict, "LOGIN_ALREADY_EXISTS")
        assertApiError(login("short"), HttpStatusCode.BadRequest, "INVALID_DATA")
        assertApiError(login("wrongpass"), HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS")
        for (status in AccountStatus.entries) {
            repo.users["user.name"] = repo.users.getValue("user.name").copy(status = status)
            val logged = login(); assertEquals(HttpStatusCode.OK, logged.status)
            assertEquals(status.name, json(logged.bodyAsText()).getValue("user").jsonObject.getValue("status").jsonPrimitive.content)
        }
        suspend fun me(value: String?) = client.get("/api/v1/auth/me") { if (value != null) header(HttpHeaders.Authorization, value) }
        assertEquals(HttpStatusCode.OK, me("Bearer $token").status)
        for (header in listOf(null, "Bearer x", "Bearer ${"x".repeat(43)}")) {
            assertApiError(me(header), HttpStatusCode.Unauthorized, "INVALID_SESSION")
        }
        val second = json(login().bodyAsText()).getValue("accessToken").jsonPrimitive.content
        suspend fun logout(value: String) = client.post("/api/v1/auth/logout") { header(HttpHeaders.Authorization, "Bearer $value") }
        val loggedOut = logout(token); assertEquals(HttpStatusCode.NoContent, loggedOut.status); assertEquals("", loggedOut.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, logout(token).status); assertEquals(HttpStatusCode.OK, me("Bearer $second").status)
        repeat(12) { assertEquals(HttpStatusCode.OK, login().status) }
        val before = repo.sessions.size
        assertApiError(login(), HttpStatusCode.TooManyRequests, "RATE_LIMITED")
        assertEquals(before, repo.sessions.size)
        mutableClock.current = mutableClock.current.plusSeconds(60)
        assertEquals(HttpStatusCode.OK, login().status)
    }

    @Test fun rateLimitStopsWorkAndServerErrorIsSafe() = testApplication {
        var dbCalls = 0
        var hashCalls = 0
        val counting = object : AuthRepository by repo {
            override fun <T> transaction(block: () -> T): T { dbCalls++; error("secret SQL and stack trace") }
        }
        val hasher = object : PasswordHasher {
            override fun hash(password: String): String { hashCalls++; return password }
            override fun verify(password: String, hash: String): Boolean { hashCalls++; return true }
        }
        val limited = AuthService(counting, hasher, FakeTokens(), Clock.fixed(instant, ZoneOffset.UTC))
        application { configureServer(limited, AuthRateLimiter(Clock.fixed(instant, ZoneOffset.UTC), limit = 1)) }
        val body = """{"login":"validname","password":"password"}"""
        suspend fun request() = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody(body) }
        val failed = request()
        assertApiError(failed, HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
        assertFalse(failed.bodyAsText().contains("secret")); assertFalse(failed.bodyAsText().contains("stack"))
        val denied = request(); assertEquals(HttpStatusCode.TooManyRequests, denied.status)
        assertEquals(1, dbCalls); assertEquals(0, hashCalls)
    }

    @Test fun limitedRegistrationAndLoginNeverReachDependencies() = testApplication {
        var dbCalls = 0; var hashCalls = 0
        val counting = object : AuthRepository by repo { override fun <T> transaction(block: () -> T): T { dbCalls++; return repo.transaction(block) } }
        val hasher = object : PasswordHasher {
            override fun hash(password: String): String { hashCalls++; return password }
            override fun verify(password: String, hash: String): Boolean { hashCalls++; return true }
        }
        val limiter = AuthRateLimiter(Clock.fixed(instant, ZoneOffset.UTC), limit = 1)
        application { configureServer(AuthService(counting, hasher, FakeTokens(), Clock.fixed(instant, ZoneOffset.UTC)), limiter) }
        val first = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody("{") }
        assertEquals(HttpStatusCode.BadRequest, first.status)
        val register = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"login":"validname","password":"password","lastName":"Name","firstName":"Name","grade":1,"classLetter":"A"}""")
        }
        val login = client.post("/api/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"login":"validname","password":"password"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, register.status); assertEquals(HttpStatusCode.TooManyRequests, login.status)
        assertEquals(0, dbCalls); assertEquals(0, hashCalls)
    }

    @Test fun directClientsCannotSelectTheirQuotaWithHeaders() = testApplication {
        application { configureServer(service, AuthRateLimiter(Clock.fixed(instant, ZoneOffset.UTC), limit = 1)) }

        suspend fun login(claimedIp: String) = client.post("/api/v1/auth/login") {
            header("X-Bookio-Client-IP", claimedIp)
            header("X-Forwarded-For", claimedIp)
            contentType(ContentType.Application.Json)
            setBody("""{"login":"unknown","password":"password"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, login("192.0.2.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, login("192.0.2.2").status)
    }

    @Test fun trustedProxyClientsHaveSeparateQuotasAndShareAnInstanceLimit() = testApplication {
        val limiter = AuthRateLimiter(Clock.fixed(instant, ZoneOffset.UTC), limit = 1, globalLimit = 2)
        application { configureServer(service, limiter, trustedProxyAddress = "localhost") }

        suspend fun login(clientIp: String) = client.post("/api/v1/auth/login") {
            header("X-Bookio-Client-IP", clientIp)
            contentType(ContentType.Application.Json)
            setBody("""{"login":"unknown","password":"password"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, login("192.0.2.1").status)
        assertEquals(HttpStatusCode.TooManyRequests, login("192.0.2.1").status)
        assertEquals(HttpStatusCode.Unauthorized, login("192.0.2.2").status)
        assertEquals(HttpStatusCode.TooManyRequests, login("192.0.2.3").status)
    }

}
