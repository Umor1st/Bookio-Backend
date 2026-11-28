package ru.bookio.bootstrap

import ru.bookio.infrastructure.database.DatabaseConfig

data class AppConfig(val port: Int, val database: DatabaseConfig, val authTrustedProxyAddress: String? = null) {
    companion object {
        fun fromEnvironment(env: Map<String, String>) = AppConfig(
            port = env.port("PORT"),
            database = databaseFromEnvironment(env),
            authTrustedProxyAddress = env["AUTH_TRUSTED_PROXY_ADDRESS"]?.takeIf { it.isNotBlank() },
        )

        fun databaseFromEnvironment(env: Map<String, String>) = DatabaseConfig(
            host = env.required("DB_HOST"),
            port = env.port("DB_PORT"),
            name = env.required("DB_NAME"),
            user = env.required("DB_USER"),
            password = env.required("DB_PASSWORD"),
        )

        private fun Map<String, String>.required(name: String): String = this[name]?.takeIf { it.isNotBlank() }
            ?: error("Environment variable $name is required")

        private fun Map<String, String>.port(name: String): Int = required(name).toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("Environment variable $name must be a valid port")
    }
}
