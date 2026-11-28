package ru.bookio.domain.repository

import java.time.Instant
import java.util.UUID
import ru.bookio.domain.model.User

interface LibrarianRepository {
    fun <T> transaction(block: () -> T): T
    fun findUser(login: String, lock: Boolean): User?
    fun librarianExists(): Boolean
    fun insertLibrarianIfAvailable(user: User, now: Instant): Boolean
    fun findLibrarianForUpdate(): User?
    fun updateLibrarianCredentials(userId: UUID, login: String, passwordHash: String, now: Instant)
    fun revokeUserSessions(userId: UUID, now: Instant)
}
