package ru.bookio

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.bookio.infrastructure.database.DatabaseConfig
import ru.bookio.infrastructure.database.DatabaseConnection

class DatabaseConnectionTest {
    @Test
    fun connectsToPostgresAndRunsFlyway() {
        PostgreSQLContainer("postgres:17").use { postgres ->
            postgres.start()
            val config = DatabaseConfig(postgres.host, postgres.firstMappedPort, postgres.databaseName, postgres.username, postgres.password)
            DatabaseConnection.open(config).use { connection ->
                val result = transaction(connection.database) {
                    exec("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success") {
                        rows -> if (rows.next()) rows.getInt(1) else null
                    }
                }
                assertEquals(1, result)
            }
        }
    }
}
