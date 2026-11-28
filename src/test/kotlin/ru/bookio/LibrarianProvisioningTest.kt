package ru.bookio

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.bookio.application.auth.*
import ru.bookio.bootstrap.configureServer
import ru.bookio.domain.error.AuthError
import ru.bookio.domain.error.AuthErrorCode
import ru.bookio.domain.model.Registration
import ru.bookio.domain.model.User
import ru.bookio.domain.repository.LibrarianRepository
import ru.bookio.infrastructure.database.*
import ru.bookio.infrastructure.security.BCryptPasswordHasher
import ru.bookio.infrastructure.security.SecureTokens

class LibrarianProvisioningTest {
    @Test
    fun updatePreservesTheAccountAndRevokesSessionsOnlyAfterSuccessfulPasswordReset() = runBlocking {
        PostgreSQLContainer("postgres:17").use { postgres ->
            postgres.start()
            val config = DatabaseConfig(postgres.host, postgres.firstMappedPort, postgres.databaseName, postgres.username, postgres.password)
            DatabaseConnection.open(config).use { db ->
                val repo = PostgresAuthRepository(db.database)
                val passwords = BCryptPasswordHasher()
                val clock = Clock.systemUTC()
                val auth = AuthService(repo, passwords, SecureTokens(), clock)
                val update = UpdateLibrarian(repo, passwords, clock)
                assertEquals(UpdateLibrarianResult.NOT_FOUND, update.execute("missing", null))

                val student = auth.register(Registration("Student", "student-pass", "Student", "Name", null, 8, "A"))
                val create = CreateLibrarian(repo, passwords, clock)
                assertEquals(CreateLibrarianResult.CREATED, create.execute("library", "Librarian", "Name", "Patronymic", "original-password"))
                val initial = auth.login("library", "original-password")
                assertEquals(UpdateLibrarianResult.NO_CHANGES, update.execute("LIBRARY", null))
                assertEquals(UpdateLibrarianResult.NO_CHANGES, update.execute(null, null))
                assertEquals(AuthErrorCode.INVALID_DATA, assertFailsWith<AuthError> {
                    update.execute("valid", "short")
                }.code)
                assertEquals(AuthErrorCode.INVALID_DATA, assertFailsWith<AuthError> {
                    update.execute("invalid login", null)
                }.code)
                assertEquals(UpdateLibrarianResult.LOGIN_IN_USE, update.execute("STUDENT", "unused-password"))
                assertEquals(initial.user, auth.me(initial.token))

                assertEquals(UpdateLibrarianResult.UPDATED, update.execute("Renamed", null))
                val renamed = initial.user.copy(login = "renamed")
                assertEquals(renamed, auth.me(initial.token))
                assertEquals(AuthErrorCode.INVALID_CREDENTIALS, assertFailsWith<AuthError> {
                    auth.login("library", "original-password")
                }.code)
                val secondSession = auth.login("RENAMED", "original-password")
                assertEquals(renamed, secondSession.user)

                val failingRepository = object : LibrarianRepository by repo {
                    override fun revokeUserSessions(userId: UUID, now: Instant) {
                        repo.revokeUserSessions(userId, now)
                        error("Simulated failure before commit")
                    }
                }
                assertFailsWith<IllegalStateException> {
                    UpdateLibrarian(failingRepository, passwords, clock).execute("rolled-back", "unused-password")
                }
                assertNull(repo.transaction { repo.findUser("rolled-back") })
                assertEquals(renamed, auth.me(initial.token))
                assertEquals(renamed, auth.me(secondSession.token))

                assertEquals(UpdateLibrarianResult.UPDATED, update.execute(null, "reset-password"))
                for (session in listOf(initial, secondSession)) {
                    assertEquals(AuthErrorCode.INVALID_SESSION, assertFailsWith<AuthError> { auth.me(session.token) }.code)
                }
                assertEquals(AuthErrorCode.INVALID_CREDENTIALS, assertFailsWith<AuthError> {
                    auth.login("renamed", "original-password")
                }.code)
                val reset = auth.login("renamed", "reset-password")
                assertEquals(renamed.copy(passwordHash = reset.user.passwordHash), reset.user)

                assertEquals(UpdateLibrarianResult.UPDATED, update.execute("Final.Login", "final-password"))
                assertEquals(AuthErrorCode.INVALID_SESSION, assertFailsWith<AuthError> { auth.me(reset.token) }.code)
                val final = auth.login("FINAL.LOGIN", "final-password").user
                assertEquals(renamed.copy(login = "final.login", passwordHash = final.passwordHash), final)
                assertEquals(student.user, auth.me(student.token))
            }
        }
    }

    @Test
    fun concurrentCreationPreservesTheWinningAccountAndSupportsAuth() = runBlocking {
        PostgreSQLContainer("postgres:17").use { postgres ->
            postgres.start()
            val config = DatabaseConfig(postgres.host, postgres.firstMappedPort, postgres.databaseName, postgres.username, postgres.password)
            DatabaseConnection.open(config).use { db ->
                val repo = PostgresAuthRepository(db.database)
                val passwords = BCryptPasswordHasher()
                val clock = Clock.systemUTC()
                val auth = AuthService(repo, passwords, SecureTokens(), clock)
                val create = CreateLibrarian(repo, passwords, clock)
                val student = auth.register(Registration("Student", "student-pass", "Student", "Name", null, 8, "A")).user

                assertEquals(AuthErrorCode.INVALID_DATA, assertFailsWith<AuthError> {
                    create.execute("library", " ", "Name", null, "password")
                }.code)
                assertEquals(CreateLibrarianResult.STUDENT_LOGIN_IN_USE, create.execute("STUDENT", "Other", "Name", null, "otherpass"))
                assertEquals(student, repo.transaction { repo.findUser("student") })
                assertFalse(repo.transaction { repo.librarianExists() })

                val barrier = CyclicBarrier(4)
                val concurrentRepo = object : LibrarianRepository by repo {
                    override fun insertLibrarianIfAvailable(user: User, now: Instant): Boolean {
                        barrier.await(20, TimeUnit.SECONDS)
                        return repo.insertLibrarianIfAvailable(user, now)
                    }
                }
                val concurrent = CreateLibrarian(concurrentRepo, passwords, clock)
                val attempts = listOf("Library", "LIBRARY", "Second", "SECOND")
                val outcomes = attempts.mapIndexed { index, login ->
                    async(Dispatchers.IO) {
                        concurrent.execute(login, " Librarian ${index} ", " Name ", " ", "password${index}")
                    }
                }.awaitAll()
                val winner = outcomes.indexOf(CreateLibrarianResult.CREATED)
                assertTrue(winner >= 0)
                val login = attempts[winner].lowercase()
                val winningPassword = "password${winner}"
                outcomes.forEachIndexed { index, outcome ->
                    val expected = when {
                        index == winner -> CreateLibrarianResult.CREATED
                        attempts[index].equals(login, ignoreCase = true) -> CreateLibrarianResult.ALREADY_CREATED
                        else -> CreateLibrarianResult.ANOTHER_LIBRARIAN_EXISTS
                    }
                    assertEquals(expected, outcome)
                }

                val before = assertNotNull(repo.transaction { repo.findUser(login) })
                assertEquals("Librarian ${winner}", before.lastName)
                assertEquals("Name", before.firstName)
                assertTrue(passwords.verify(winningPassword, before.passwordHash))
                assertEquals(CreateLibrarianResult.ALREADY_CREATED, create.execute(login, "Changed", "Name", "Patronymic", "changedpass"))
                assertEquals(before, repo.transaction { repo.findUser(login) })

                testApplication {
                    application { configureServer(auth) }
                    suspend fun loginWith(password: String) = client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"login":"${attempts[winner]}","password":"$password"}""")
                    }
                    val response = loginWith(winningPassword)
                    assertEquals(HttpStatusCode.OK, response.status)
                    val user = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("user").jsonObject
                    assertEquals("LIBRARIAN", user.getValue("role").jsonPrimitive.content)
                    assertEquals("ACTIVE", user.getValue("status").jsonPrimitive.content)
                    for (field in listOf("middleName", "grade", "classLetter")) assertEquals(JsonNull, user.getValue(field))
                    assertEquals(HttpStatusCode.Unauthorized, loginWith("changedpass").status)
                }
            }
        }
    }
}
