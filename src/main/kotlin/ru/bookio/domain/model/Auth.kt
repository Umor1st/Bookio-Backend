package ru.bookio.domain.model

import java.time.Instant
import java.util.UUID

enum class Role { STUDENT, LIBRARIAN }
enum class AccountStatus { PENDING, ACTIVE, REJECTED }
data class User(val id: UUID, val login: String, val passwordHash: String, val lastName: String, val firstName: String, val middleName: String?, val grade: Int?, val classLetter: String?, val role: Role, val status: AccountStatus)
data class Session(val id: UUID, val userId: UUID, val tokenHash: String, val createdAt: Instant, val expiresAt: Instant)
data class AuthResult(val token: String, val expiresAt: Instant, val user: User)
data class Registration(val login: String, val password: String, val lastName: String, val firstName: String, val middleName: String?, val grade: Int, val classLetter: String)
