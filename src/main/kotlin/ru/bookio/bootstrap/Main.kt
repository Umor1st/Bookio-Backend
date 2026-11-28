package ru.bookio.bootstrap

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import java.time.Clock
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import ru.bookio.application.auth.AuthService
import ru.bookio.domain.error.*
import ru.bookio.infrastructure.database.*
import ru.bookio.infrastructure.security.*
import ru.bookio.presentation.dto.*
import ru.bookio.presentation.routes.*

fun Application.configureServer(
    auth: AuthService,
    limiter: AuthRateLimiter = AuthRateLimiter(Clock.systemUTC()),
    trustedProxyAddress: String? = null,
) {
    install(ContentNegotiation) { json(Json { explicitNulls = true; encodeDefaults = true }) }
    install(StatusPages) {
        exception<AuthError> { call, error ->
            val (status, message) = when (error.code) {
                AuthErrorCode.RATE_LIMITED -> HttpStatusCode.TooManyRequests to "Too many requests. Try again later."
                AuthErrorCode.INVALID_DATA -> HttpStatusCode.BadRequest to "Invalid request data."
                AuthErrorCode.INVALID_CREDENTIALS -> HttpStatusCode.Unauthorized to "Incorrect login or password."
                AuthErrorCode.INVALID_SESSION -> HttpStatusCode.Unauthorized to "Session is missing or invalid. Sign in again."
                AuthErrorCode.LOGIN_ALREADY_EXISTS -> HttpStatusCode.Conflict to "This login is already in use."
            }
            call.respond(status, ErrorResponse(ErrorBody(error.code.name, message)))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorBody("INVALID_DATA", "Invalid request data.")))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ErrorResponse(ErrorBody("UNSUPPORTED_MEDIA_TYPE", "Unsupported request content type.")),
            )
        }
        exception<CancellationException> { _, error -> throw error }
        exception<Exception> { call, error ->
            this@configureServer.log.error(
                "Unhandled request failure (type={}, causeType={}, location={})",
                error.javaClass.name,
                error.cause?.javaClass?.name,
                error.stackTrace.firstOrNull(),
            )
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorBody("INTERNAL_ERROR", "Internal server error")))
        }
    }
    healthRoutes()
    authRoutes(auth, limiter, trustedProxyAddress)
}
fun main(args: Array<String>) {
    val env = System.getenv()
    when {
        args.isEmpty() -> startServer(AppConfig.fromEnvironment(env))
        args.contentEquals(arrayOf("create-librarian")) -> exitProcess(runCreateLibrarian(env))
        args.contentEquals(arrayOf("update-librarian")) -> exitProcess(runUpdateLibrarian(env))
        else -> {
            System.err.println("Usage: Bookio-Backend [create-librarian|update-librarian]")
            exitProcess(2)
        }
    }
}

private fun startServer(config: AppConfig) {
    DatabaseConnection.open(config.database).use { connection ->
        val auth = AuthService(PostgresAuthRepository(connection.database), BCryptPasswordHasher(), SecureTokens(), Clock.systemUTC())
        embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
            configureServer(auth, trustedProxyAddress = config.authTrustedProxyAddress)
        }.start(wait = true)
    }
}
