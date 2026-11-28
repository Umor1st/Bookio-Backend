package ru.bookio.application.auth

import java.time.Clock
import ru.bookio.domain.error.AuthError
import ru.bookio.domain.error.AuthErrorCode
import ru.bookio.domain.repository.LibrarianRepository
import ru.bookio.domain.service.PasswordHasher

enum class UpdateLibrarianResult {
    UPDATED,
    NO_CHANGES,
    NOT_FOUND,
    LOGIN_IN_USE,
}

class UpdateLibrarian(
    private val repository: LibrarianRepository,
    private val passwords: PasswordHasher,
    private val clock: Clock,
) {
    fun execute(newLogin: String?, newPassword: String?): UpdateLibrarianResult {
        val login = newLogin?.let(AuthInputRules::login)
        val passwordHash = newPassword?.let {
            AuthInputRules.password(it)
            passwords.hash(it)
        }
        return try {
            repository.transaction {
                val librarian = repository.findLibrarianForUpdate() ?: return@transaction UpdateLibrarianResult.NOT_FOUND
                if (passwordHash == null && (login == null || login == librarian.login)) {
                    return@transaction UpdateLibrarianResult.NO_CHANGES
                }
                if (login != null && login != librarian.login && repository.findUser(login, lock = false) != null) {
                    return@transaction UpdateLibrarianResult.LOGIN_IN_USE
                }

                val now = clock.instant()
                repository.updateLibrarianCredentials(librarian.id, login ?: librarian.login, passwordHash ?: librarian.passwordHash, now)
                if (passwordHash != null) repository.revokeUserSessions(librarian.id, now)
                UpdateLibrarianResult.UPDATED
            }
        } catch (error: AuthError) {
            if (error.code != AuthErrorCode.LOGIN_ALREADY_EXISTS) throw error
            UpdateLibrarianResult.LOGIN_IN_USE
        }
    }
}
