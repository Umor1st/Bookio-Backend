package ru.bookio.domain.repository

import java.time.Instant
import java.util.UUID
import ru.bookio.domain.model.*

interface AuthRepository {
    fun <T> transaction(block: () -> T): T
    fun findUser(login: String, lock: Boolean = false): User?
    fun saveUser(user: User, now: Instant, existing: Boolean)
    fun revokeUserSessions(userId: UUID, now: Instant)
    fun createSession(session: Session)
    fun findSessionUser(hash: String, now: Instant): User?
    fun revokeSession(hash: String, now: Instant): Boolean
}
