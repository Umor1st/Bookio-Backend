package ru.bookio.domain.error

enum class AuthErrorCode { RATE_LIMITED, INVALID_DATA, INVALID_CREDENTIALS, INVALID_SESSION, LOGIN_ALREADY_EXISTS }
class AuthError(val code: AuthErrorCode) : RuntimeException(code.name)
