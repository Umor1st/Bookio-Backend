package ru.bookio.presentation.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.bookio.application.auth.AuthService
import ru.bookio.domain.error.*
import ru.bookio.presentation.dto.*
import ru.bookio.presentation.mapper.*

private val bearerPattern = Regex("(?i)^Bearer ([A-Za-z0-9_-]{43})$")
private fun ApplicationCall.bearerToken(): String = request.headers[HttpHeaders.Authorization]
    ?.let { bearerPattern.matchEntire(it)?.groupValues?.get(1) } ?: throw AuthError(AuthErrorCode.INVALID_SESSION)

private suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T {
    val contentType = request.contentType()
    if (!contentType.match(ContentType.Application.Json)) throw UnsupportedMediaTypeException(contentType)
    return receive()
}

private fun ApplicationCall.authClientAddress(trustedProxyAddress: String?): String {
    val peer = request.local.remoteAddress
    if (peer == trustedProxyAddress) {
        return request.headers.getAll("X-Bookio-Client-IP")?.singleOrNull()?.takeIf { it.isNotBlank() } ?: peer
    }
    return peer
}

fun Application.authRoutes(auth: AuthService, limiter: AuthRateLimiter, trustedProxyAddress: String?) {
    routing {
        route("/api/v1/auth") {
            post("/register") {
                if (!limiter.allow(call.authClientAddress(trustedProxyAddress))) throw AuthError(AuthErrorCode.RATE_LIMITED)
                call.respond(HttpStatusCode.Created, auth.register(call.receiveJson<RegisterRequest>().toDomain()).toDto())
            }
            post("/login") {
                if (!limiter.allow(call.authClientAddress(trustedProxyAddress))) throw AuthError(AuthErrorCode.RATE_LIMITED)
                val request = call.receiveJson<LoginRequest>()
                call.respond(auth.login(request.login, request.password).toDto())
            }
            post("/logout") { auth.logout(call.bearerToken()); call.respond(HttpStatusCode.NoContent) }
            get("/me") { call.respond(auth.me(call.bearerToken()).toDto()) }
        }
    }
}
