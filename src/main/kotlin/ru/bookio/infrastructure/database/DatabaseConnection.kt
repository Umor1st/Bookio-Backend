package ru.bookio.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

data class DatabaseConfig(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
)

class DatabaseConnection private constructor(
    private val dataSource: HikariDataSource,
    val database: Database,
) : AutoCloseable {
    override fun close() = dataSource.close()

    companion object {
        fun open(config: DatabaseConfig): DatabaseConnection {
            val hikari = HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://${config.host}:${config.port}/${config.name}"
                username = config.user
                password = config.password
                maximumPoolSize = 5
            }
            val source = HikariDataSource(hikari)
            try {
                Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
                return DatabaseConnection(source, Database.connect(source))
            } catch (exception: Exception) {
                source.close()
                throw exception
            }
        }
    }
}
