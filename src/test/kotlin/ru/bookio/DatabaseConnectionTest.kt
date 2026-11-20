package ru.bookio

import kotlin.test.*
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import ru.bookio.application.auth.AuthService
import ru.bookio.domain.model.Registration
import ru.bookio.domain.model.AccountStatus
import ru.bookio.domain.model.Role
import ru.bookio.domain.error.AuthError
import ru.bookio.domain.error.AuthErrorCode
import ru.bookio.infrastructure.security.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.bookio.infrastructure.database.*

class DatabaseConnectionTest {
    @Test fun connectsToPostgresAndRunsRealMigrations() = runBlocking {
        PostgreSQLContainer("postgres:17").use { postgres ->
            postgres.start()
            DatabaseConnection.open(DatabaseConfig(postgres.host, postgres.firstMappedPort, postgres.databaseName, postgres.username, postgres.password)).use { connection ->
                val count = transaction(connection.database) {
                    exec("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('users', 'sessions')") { rows -> rows.next(); rows.getInt(1) }
                }
                assertEquals(2, count)
                val auth = AuthService(PostgresAuthRepository(connection.database), BCryptPasswordHasher(), SecureTokens(), Clock.systemUTC())
                val input = Registration("Concurrent", "password", "Name", "First", null, 1, "A")
                val pool = Executors.newFixedThreadPool(2)
                val start = CountDownLatch(1)
                try {
                    val tasks = (1..2).map { pool.submit<String> { start.await(); try { runBlocking { auth.register(input) }; "created" } catch (e: AuthError) { e.code.name } } }
                    start.countDown()
                    assertEquals(listOf("LOGIN_ALREADY_EXISTS", "created"), tasks.map { it.get() }.sorted())
                    assertEquals("concurrent", auth.login("CONCURRENT", "password").user.login)
                } finally { pool.shutdownNow() }
                val oldSession = auth.login("concurrent", "password")
                val createdAt = transaction(connection.database) {
                    exec("SELECT created_at FROM users WHERE login = 'concurrent'") { rows -> rows.next(); rows.getTimestamp(1).toInstant() }
                }
                transaction(connection.database) { exec("UPDATE users SET status = 'REJECTED' WHERE login = 'concurrent'") }
                val updated = auth.register(input.copy(password = "newpass1", lastName = " Updated ", firstName = " New ", middleName = " Middle ", grade = 11, classLetter = " B "))
                assertEquals(oldSession.user.id, updated.user.id)
                assertEquals("concurrent", updated.user.login)
                assertEquals("Updated", updated.user.lastName)
                assertEquals("New", updated.user.firstName)
                assertEquals("Middle", updated.user.middleName)
                assertEquals(11, updated.user.grade)
                assertEquals("B", updated.user.classLetter)
                assertEquals(Role.STUDENT, updated.user.role)
                assertEquals(AccountStatus.PENDING, updated.user.status)
                assertEquals(createdAt, transaction(connection.database) {
                    exec("SELECT created_at FROM users WHERE login = 'concurrent'") { rows -> rows.next(); rows.getTimestamp(1).toInstant() }
                })
                assertEquals(AuthErrorCode.INVALID_SESSION, assertFailsWith<AuthError> { auth.me(oldSession.token) }.code)
                assertEquals(AuthErrorCode.INVALID_CREDENTIALS, assertFailsWith<AuthError> { auth.login("concurrent", "password") }.code)
                assertEquals(updated.user.id, auth.login("concurrent", "newpass1").user.id)
                assertFails { transaction(connection.database) { exec("INSERT INTO users (id, login, password_hash, last_name, first_name, role, status, created_at, updated_at) VALUES ('00000000-0000-0000-0000-000000000001', 'BAD', repeat('x', 60), 'A', 'B', 'STUDENT', 'PENDING', now(), now())") } }
                Unit
            }
        }
    }
}
