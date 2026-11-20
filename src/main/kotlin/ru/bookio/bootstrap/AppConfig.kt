package ru.bookio.bootstrap

import ru.bookio.infrastructure.database.DatabaseConfig

data class AppConfig(val port: Int, val database: DatabaseConfig, val authTrustedProxyAddress: String? = null) {
    companion object {
        fun fromEnvironment(env: Map<String, String>): AppConfig {
            fun required(name: String): String = env[name]?.takeIf { it.isNotBlank() }
                ?: error("Environment variable $name is required")
            fun port(name: String): Int = required(name).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: error("Environment variable $name must be a valid port")

            return AppConfig(
                port = port("PORT"),
                database = DatabaseConfig(
                    host = required("DB_HOST"),
                    port = port("DB_PORT"),
                    name = required("DB_NAME"),
                    user = required("DB_USER"),
                    password = required("DB_PASSWORD"),
                ),
                authTrustedProxyAddress = env["AUTH_TRUSTED_PROXY_ADDRESS"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
