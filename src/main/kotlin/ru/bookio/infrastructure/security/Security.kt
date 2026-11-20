package ru.bookio.infrastructure.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import ru.bookio.domain.service.*

class BCryptPasswordHasher : PasswordHasher {
    private val encoder = BCryptPasswordEncoder(12)
    override fun hash(password: String): String = requireNotNull(encoder.encode(password))
    override fun verify(password: String, hash: String) = encoder.matches(password, hash)
}
class SecureTokens : Tokens {
    private val random = SecureRandom()
    override fun generate(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    override fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
}
