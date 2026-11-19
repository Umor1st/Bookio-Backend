package ru.bookio.bootstrap

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import ru.bookio.infrastructure.database.DatabaseConnection
import ru.bookio.presentation.routes.healthRoutes

fun main() {
    val config = AppConfig.fromEnvironment(System.getenv())
    DatabaseConnection.open(config.database).use {
        embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
            healthRoutes()
        }.start(wait = true)
    }
}
