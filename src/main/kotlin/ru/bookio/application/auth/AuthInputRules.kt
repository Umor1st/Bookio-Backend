package ru.bookio.application.auth

import java.util.Locale
import ru.bookio.domain.error.AuthError
import ru.bookio.domain.error.AuthErrorCode

internal object AuthInputRules {
    private val loginPattern = Regex("[a-zA-Z0-9._-]{3,32}")
    private val passwordPattern = Regex("[!-~]{8,64}")

    fun invalid(): Nothing = throw AuthError(AuthErrorCode.INVALID_DATA)

    fun login(value: String): String {
        if (!loginPattern.matches(value)) invalid()
        return value.lowercase(Locale.ROOT)
    }

    fun password(value: String) {
        if (!passwordPattern.matches(value)) invalid()
    }

    fun name(value: String) = field(value, 100)

    fun middleName(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(::name)

    fun field(value: String, max: Int): String = value.trim().also { if (it.length !in 1..max) invalid() }
}
