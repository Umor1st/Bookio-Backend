package ru.bookio.application.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.util.UUID
import ru.bookio.domain.error.*
import ru.bookio.domain.model.*
import ru.bookio.domain.repository.AuthRepository
import ru.bookio.domain.service.*

class AuthService(private val repository: AuthRepository, private val passwords: PasswordHasher, private val tokens: Tokens, private val clock: Clock) {
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(5)
    private val passwordDispatcher = Dispatchers.Default.limitedParallelism(2)
    private fun issue(user: User): AuthResult {
        val now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val token = tokens.generate()
        val expires = now.plus(Duration.ofDays(30))
        repository.createSession(Session(UUID.randomUUID(), user.id, tokens.hash(token), now, expires))
        return AuthResult(token, expires, user)
    }
    suspend fun register(input: Registration): AuthResult {
        val login = AuthInputRules.login(input.login)
        AuthInputRules.password(input.password)
        val last = AuthInputRules.name(input.lastName)
        val first = AuthInputRules.name(input.firstName)
        val middle = AuthInputRules.middleName(input.middleName)
        val letter = AuthInputRules.field(input.classLetter, 5)
        if (input.grade !in 1..11) AuthInputRules.invalid()
        val hash = withContext(passwordDispatcher) { passwords.hash(input.password) }
        return withContext(dbDispatcher) { repository.transaction {
            val existing = repository.findUser(login, true)
            if (existing != null && (existing.role != Role.STUDENT || existing.status != AccountStatus.REJECTED)) throw AuthError(AuthErrorCode.LOGIN_ALREADY_EXISTS)
            val now = clock.instant()
            val user = User(existing?.id ?: UUID.randomUUID(), login, hash, last, first, middle, input.grade, letter, Role.STUDENT, AccountStatus.PENDING)
            repository.saveUser(user, now, existing != null)
            if (existing != null) repository.revokeUserSessions(user.id, now)
            issue(user)
        } }
    }
    suspend fun login(login: String, password: String): AuthResult {
        val normalized = AuthInputRules.login(login)
        AuthInputRules.password(password)
        val candidate = withContext(dbDispatcher) { repository.transaction { repository.findUser(normalized) } }
        if (candidate == null || !withContext(passwordDispatcher) { passwords.verify(password, candidate.passwordHash) }) throw AuthError(AuthErrorCode.INVALID_CREDENTIALS)
        return withContext(dbDispatcher) { repository.transaction {
            val user = repository.findUser(normalized, true)
            if (user == null || user.id != candidate.id || user.passwordHash != candidate.passwordHash) throw AuthError(AuthErrorCode.INVALID_CREDENTIALS)
            issue(user)
        } }
    }

    suspend fun me(token: String): User = withContext(dbDispatcher) {
        repository.transaction { repository.findSessionUser(tokens.hash(token), clock.instant()) ?: throw AuthError(AuthErrorCode.INVALID_SESSION) }
    }
    suspend fun logout(token: String) = withContext(dbDispatcher) {
        repository.transaction { if (!repository.revokeSession(tokens.hash(token), clock.instant())) throw AuthError(AuthErrorCode.INVALID_SESSION) }
    }
}
