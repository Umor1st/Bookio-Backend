package ru.bookio.application.auth

import java.time.Clock
import java.util.UUID
import ru.bookio.domain.model.AccountStatus
import ru.bookio.domain.model.Role
import ru.bookio.domain.model.User
import ru.bookio.domain.repository.LibrarianRepository
import ru.bookio.domain.service.PasswordHasher

enum class CreateLibrarianResult {
    CREATED,
    ALREADY_CREATED,
    STUDENT_LOGIN_IN_USE,
    ANOTHER_LIBRARIAN_EXISTS,
}

class CreateLibrarian(
    private val repository: LibrarianRepository,
    private val passwords: PasswordHasher,
    private val clock: Clock,
) {
    fun execute(login: String, lastName: String, firstName: String, middleName: String?, password: String): CreateLibrarianResult {
        val normalizedLogin = AuthInputRules.login(login)
        AuthInputRules.password(password)
        val last = AuthInputRules.name(lastName)
        val first = AuthInputRules.name(firstName)
        val middle = AuthInputRules.middleName(middleName)
        val user = User(
            id = UUID.randomUUID(),
            login = normalizedLogin,
            passwordHash = passwords.hash(password),
            lastName = last,
            firstName = first,
            middleName = middle,
            grade = null,
            classLetter = null,
            role = Role.LIBRARIAN,
            status = AccountStatus.ACTIVE,
        )
        return repository.transaction {
            if (repository.insertLibrarianIfAvailable(user, clock.instant())) {
                CreateLibrarianResult.CREATED
            } else {
                when (repository.findUser(normalizedLogin, lock = false)?.role) {
                    Role.LIBRARIAN -> CreateLibrarianResult.ALREADY_CREATED
                    Role.STUDENT -> CreateLibrarianResult.STUDENT_LOGIN_IN_USE
                    null -> {
                        check(repository.librarianExists()) { "Conflicting librarian insert has no matching account" }
                        CreateLibrarianResult.ANOTHER_LIBRARIAN_EXISTS
                    }
                }
            }
        }
    }
}
