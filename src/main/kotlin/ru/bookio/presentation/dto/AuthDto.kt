package ru.bookio.presentation.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class RegisterRequest(val login: String, val password: String, val lastName: String, val firstName: String, val middleName: String? = null, val grade: Int, val classLetter: String)
@Serializable data class LoginRequest(val login: String, val password: String)
@Serializable data class UserDto(val id: String, val login: String, val lastName: String, val firstName: String, val middleName: String?, val grade: Int?, val classLetter: String?, val role: String, val status: String)
@Serializable data class AuthResponse(val accessToken: String, val tokenType: String, val expiresAt: String, val user: UserDto)
@Serializable data class ErrorBody(val code: String, val message: String, val details: JsonObject = JsonObject(emptyMap()))
@Serializable data class ErrorResponse(val error: ErrorBody)
