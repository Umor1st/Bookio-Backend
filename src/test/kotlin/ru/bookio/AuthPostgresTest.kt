package ru.bookio

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import ru.bookio.bootstrap.configureServer
import java.time.*
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.bookio.application.auth.AuthService
import ru.bookio.domain.error.*
import ru.bookio.domain.model.*
import ru.bookio.domain.repository.AuthRepository
import ru.bookio.domain.service.PasswordHasher
import ru.bookio.infrastructure.database.*
import ru.bookio.infrastructure.security.*

class AuthPostgresTest {
    @Test fun rollbackRaceAndTime() = runBlocking {
        PostgreSQLContainer("postgres:17").use { postgres ->
            postgres.start()
            val config = DatabaseConfig(postgres.host, postgres.firstMappedPort, postgres.databaseName, postgres.username, postgres.password)
            DatabaseConnection.open(config).use { db ->
                val repo = PostgresAuthRepository(db.database)
                val passwords = BCryptPasswordHasher()
                val now = Instant.parse("2026-01-01T12:34:56.123456Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val auth = AuthService(repo, passwords, SecureTokens(), clock)
                val input = Registration("rollback", "password", "Old", "Name", null, 4, "A")
                val failing = object : AuthRepository by repo { override fun createSession(session: Session) { error("session insert failed") } }
                val broken = AuthService(failing, passwords, SecureTokens(), clock)
                assertFailsWith<IllegalStateException> { broken.register(input) }
                assertNull(repo.transaction { repo.findUser("rollback") })
                val original = auth.register(input)
                transaction(db.database) { exec("UPDATE users SET status = 'REJECTED' WHERE login = 'rollback'") }
                val before = repo.transaction { repo.findUser("rollback") }!!
                assertFailsWith<IllegalStateException> { broken.register(input.copy(password = "newpass1", lastName = "Changed")) }
                assertEquals(before, repo.transaction { repo.findUser("rollback") })
                assertEquals(original.user.id, auth.me(original.token).id)

                val started = CountDownLatch(1); val release = CountDownLatch(1)
                val delayed = object : PasswordHasher by passwords {
                    override fun verify(password: String, hash: String): Boolean {
                        val result = passwords.verify(password, hash)
                        started.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                        return result
                    }
                }
                val loginService = AuthService(repo, delayed, SecureTokens(), clock)
                val login = async { runCatching { loginService.login("rollback", "password") } }
                assertTrue(withContext(Dispatchers.IO) { started.await(20, TimeUnit.SECONDS) })
                try {
                    val renewed = auth.register(input.copy(password = "newpass1"))
                    release.countDown()
                    assertEquals(AuthErrorCode.INVALID_CREDENTIALS, (login.await().exceptionOrNull() as AuthError).code)
                    assertEquals(AuthErrorCode.INVALID_SESSION, assertFailsWith<AuthError> { auth.me(original.token) }.code)
                    assertEquals(renewed.user.id, auth.login("rollback", "newpass1").user.id)
                } finally { release.countDown() }

                val hash = passwords.hash("password")
                transaction(db.database) { exec("INSERT INTO users (id, login, password_hash, last_name, first_name, role, status, created_at, updated_at) VALUES ('00000000-0000-0000-0000-000000000099', 'librarian', '$hash', 'Library', 'User', 'LIBRARIAN', 'REJECTED', now(), now())") }
                val librarianSession = auth.login("librarian", "password")
                val librarianBefore = repo.transaction { repo.findUser("librarian") }
                assertEquals(AuthErrorCode.LOGIN_ALREADY_EXISTS, assertFailsWith<AuthError> { auth.register(input.copy(login = "librarian")) }.code)
                assertEquals(librarianBefore, repo.transaction { repo.findUser("librarian") })
                assertEquals(Role.LIBRARIAN, auth.me(librarianSession.token).role)
                transaction(db.database) { exec("UPDATE users SET status = 'ACTIVE' WHERE login = 'librarian'") }
                testApplication {
                    application { configureServer(auth) }
                    val response = client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"login":"LIBRARIAN","password":"password"}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val token = payload.getValue("accessToken").jsonPrimitive.content
                    val user = payload.getValue("user").jsonObject
                    assertEquals("LIBRARIAN", user.getValue("role").jsonPrimitive.content)
                    assertEquals("ACTIVE", user.getValue("status").jsonPrimitive.content)
                    assertEquals(JsonNull, user.getValue("grade")); assertEquals(JsonNull, user.getValue("classLetter")); assertEquals(JsonNull, user.getValue("middleName"))
                    transaction(db.database) { exec("UPDATE users SET status = 'REJECTED' WHERE login = 'librarian'") }
                    val me = client.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }
                    assertEquals(HttpStatusCode.OK, me.status)
                    assertEquals("REJECTED", Json.parseToJsonElement(me.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
                }

                val savedZone = TimeZone.getDefault()
                try {
                    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
                    DatabaseConnection.open(config).use { zonedDb ->
                        val zonedRepo = object : AuthRepository by PostgresAuthRepository(zonedDb.database) {
                            override fun <T> transaction(block: () -> T): T = transaction(zonedDb.database) {
                                exec("SET LOCAL TIME ZONE 'Asia/Tokyo'")
                                val zone = exec("SHOW TIME ZONE") { rows -> rows.next(); rows.getString(1) }
                                assertEquals("Asia/Tokyo", zone)
                                block()
                            }
                        }
                        val tokens = SecureTokens()
                        val timed = AuthService(zonedRepo, passwords, tokens, clock).login("rollback", "newpass1")
                        val actual = transaction(zonedDb.database) {
                            exec("SELECT created_at, expires_at FROM sessions WHERE token_hash = '${tokens.hash(timed.token)}'") { rows ->
                                rows.next()
                                rows.getTimestamp(1).toInstant() to rows.getTimestamp(2).toInstant()
                            }
                        }!!
                        assertEquals(now, actual.first)
                        assertEquals(now.plus(Duration.ofDays(30)), actual.second)
                        assertEquals(actual.second, timed.expiresAt)

                        val beforeExpiry = Clock.fixed(actual.second.minusNanos(1_000), ZoneOffset.UTC)
                        assertEquals(timed.user.id, AuthService(zonedRepo, passwords, tokens, beforeExpiry).me(timed.token).id)
                        val expired = AuthService(zonedRepo, passwords, tokens, Clock.fixed(actual.second, ZoneOffset.UTC))
                        testApplication {
                            application { configureServer(expired) }
                            val response = client.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer ${timed.token}") }
                            assertEquals(HttpStatusCode.Unauthorized, response.status)
                            assertEquals("INVALID_SESSION", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                        }
                    }
                } finally { TimeZone.setDefault(savedZone) }
            }
        }
    }
}
