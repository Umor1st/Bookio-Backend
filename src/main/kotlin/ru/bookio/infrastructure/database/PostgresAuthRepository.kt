package ru.bookio.infrastructure.database

import java.time.Instant
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.timestamp
import org.postgresql.util.PSQLException
import ru.bookio.domain.error.*
import ru.bookio.domain.model.*
import ru.bookio.domain.repository.AuthRepository
import ru.bookio.domain.repository.LibrarianRepository

private object Users : Table("users") {
    val id = uuid("id"); val login = varchar("login", 32); val passwordHash = varchar("password_hash", 60)
    val lastName = varchar("last_name", 100); val firstName = varchar("first_name", 100); val middleName = varchar("middle_name", 100).nullable()
    val grade = short("grade").nullable(); val classLetter = varchar("class_letter", 5).nullable()
    val role = varchar("role", 16); val status = varchar("status", 16)
    val createdAt = timestamp("created_at"); val updatedAt = timestamp("updated_at")
}
private object Sessions : Table("sessions") {
    val id = uuid("id"); val userId = uuid("user_id"); val hash = varchar("token_hash", 64)
    val createdAt = timestamp("created_at"); val expiresAt = timestamp("expires_at"); val revokedAt = timestamp("revoked_at").nullable()
}
class PostgresAuthRepository(private val database: Database) : AuthRepository, LibrarianRepository {
    override fun <T> transaction(block: () -> T): T = try {
        transaction(database) { block() }
    } catch (e: Exception) {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is PSQLException && cause.sqlState == "23505" && cause.serverErrorMessage?.constraint == "users_login_key") throw AuthError(AuthErrorCode.LOGIN_ALREADY_EXISTS)
            cause = cause.cause
        }
        throw e
    }
    private fun row(row: ResultRow) = User(row[Users.id].toJavaUuid(), row[Users.login], row[Users.passwordHash], row[Users.lastName], row[Users.firstName], row[Users.middleName], row[Users.grade]?.toInt(), row[Users.classLetter], Role.valueOf(row[Users.role]), AccountStatus.valueOf(row[Users.status]))
    override fun findUser(login: String, lock: Boolean): User? {
        val query = Users.selectAll().where { Users.login eq login }
        if (lock) query.forUpdate()
        return query.singleOrNull()?.let(::row)
    }
    override fun librarianExists(): Boolean = !Users.select(Users.id)
        .where { Users.role eq Role.LIBRARIAN.name }
        .limit(1)
        .empty()

    override fun insertLibrarianIfAvailable(user: User, now: Instant): Boolean = Users.insertIgnore {
        setNewUserFields(it, user, now)
    }.insertedCount == 1

    override fun findLibrarianForUpdate(): User? = Users.selectAll()
        .where { Users.role eq Role.LIBRARIAN.name }
        .forUpdate()
        .singleOrNull()?.let(::row)

    override fun updateLibrarianCredentials(userId: UUID, login: String, passwordHash: String, now: Instant) {
        val updated = Users.update({ (Users.id eq userId.toKotlinUuid()) and (Users.role eq Role.LIBRARIAN.name) }) {
            it[Users.login] = login
            it[Users.passwordHash] = passwordHash
            it[updatedAt] = now
        }
        check(updated == 1)
    }

    private fun setNewUserFields(statement: UpdateBuilder<*>, user: User, now: Instant) {
        statement[Users.id] = user.id.toKotlinUuid()
        statement[Users.login] = user.login
        statement[Users.createdAt] = now
        setUserFields(statement, user, now)
    }

    private fun setUserFields(statement: UpdateBuilder<*>, user: User, now: Instant) {
        statement[Users.passwordHash] = user.passwordHash
        statement[Users.lastName] = user.lastName
        statement[Users.firstName] = user.firstName
        statement[Users.middleName] = user.middleName
        statement[Users.grade] = user.grade?.toShort()
        statement[Users.classLetter] = user.classLetter
        statement[Users.role] = user.role.name
        statement[Users.status] = user.status.name
        statement[Users.updatedAt] = now
    }
    override fun saveUser(user: User, now: Instant, existing: Boolean) {
        if (existing) Users.update({ Users.id eq user.id.toKotlinUuid() }) {
            setUserFields(it, user, now)
        } else Users.insert {
            setNewUserFields(it, user, now)
        }
    }
    override fun revokeUserSessions(userId: UUID, now: Instant) { Sessions.update({ (Sessions.userId eq userId.toKotlinUuid()) and Sessions.revokedAt.isNull() }) { it[revokedAt] = now } }
    override fun createSession(session: Session) { Sessions.insert { it[id] = session.id.toKotlinUuid(); it[userId] = session.userId.toKotlinUuid(); it[hash] = session.tokenHash; it[createdAt] = session.createdAt; it[expiresAt] = session.expiresAt; it[revokedAt] = null } }
    override fun findSessionUser(hash: String, now: Instant): User? = (Sessions.join(Users, JoinType.INNER, Sessions.userId, Users.id)).selectAll().where { (Sessions.hash eq hash) and Sessions.revokedAt.isNull() and (Sessions.expiresAt greater now) }.singleOrNull()?.let(::row)
    override fun revokeSession(hash: String, now: Instant): Boolean = Sessions.update({ (Sessions.hash eq hash) and Sessions.revokedAt.isNull() and (Sessions.expiresAt greater now) }) { it[revokedAt] = now } == 1
}
