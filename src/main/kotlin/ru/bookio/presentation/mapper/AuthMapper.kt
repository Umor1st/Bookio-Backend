package ru.bookio.presentation.mapper

import ru.bookio.domain.model.*
import ru.bookio.presentation.dto.*

fun RegisterRequest.toDomain() = Registration(login, password, lastName, firstName, middleName, grade, classLetter)
fun User.toDto() = UserDto(id.toString(), login, lastName, firstName, middleName, grade, classLetter, role.name, status.name)
fun AuthResult.toDto() = AuthResponse(token, "Bearer", expiresAt.toString(), user.toDto())
