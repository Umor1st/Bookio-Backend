package ru.bookio.bootstrap

import java.io.Console
import java.io.IOError
import java.time.Clock
import ru.bookio.application.auth.CreateLibrarian
import ru.bookio.application.auth.CreateLibrarianResult
import ru.bookio.application.auth.UpdateLibrarian
import ru.bookio.application.auth.UpdateLibrarianResult
import ru.bookio.domain.error.AuthError
import ru.bookio.domain.error.AuthErrorCode
import ru.bookio.infrastructure.database.DatabaseConfig
import ru.bookio.infrastructure.database.DatabaseConnection
import ru.bookio.infrastructure.database.PostgresAuthRepository
import ru.bookio.infrastructure.security.BCryptPasswordHasher

private const val CREDENTIAL_RULES = "Use a 3–32 character login (A–Z, a–z, 0–9, ., _, -) " +
    "and an 8–64 character printable ASCII password without spaces."

internal fun runCreateLibrarian(env: Map<String, String>): Int = withLibrarianConsole(
    "create-librarian", env, "Invalid profile. $CREDENTIAL_RULES Names must be up to 100 characters.",
) { console, config ->
    val login = console.readLine("Login: ") ?: return@withLibrarianConsole inputEnded()
    val lastName = console.readLine("Last name: ") ?: return@withLibrarianConsole inputEnded()
    val firstName = console.readLine("First name: ") ?: return@withLibrarianConsole inputEnded()
    val middleName = console.readLine("Middle name (optional): ") ?: return@withLibrarianConsole inputEnded()
    val password = console.readPassword("Password: ") ?: return@withLibrarianConsole inputEnded()
    val result = try {
        DatabaseConnection.open(config).use { connection ->
            val create = CreateLibrarian(
                PostgresAuthRepository(connection.database),
                BCryptPasswordHasher(),
                Clock.systemUTC(),
            )
            create.execute(login, lastName, firstName, middleName, password.concatToString())
        }
    } finally {
        password.fill('\u0000')
    }
    when (result) {
        CreateLibrarianResult.CREATED -> {
            println("Librarian created.")
            0
        }
        CreateLibrarianResult.ALREADY_CREATED -> {
            println("Librarian already exists with this login. Profile and password are unchanged.")
            0
        }
        CreateLibrarianResult.STUDENT_LOGIN_IN_USE -> commandError("This login belongs to a student.", 3)
        CreateLibrarianResult.ANOTHER_LIBRARIAN_EXISTS -> commandError("A librarian with a different login already exists.", 3)
    }
}

internal fun runUpdateLibrarian(env: Map<String, String>): Int = withLibrarianConsole(
    "update-librarian", env, "Invalid credentials. $CREDENTIAL_RULES",
) { console, config ->
    val login = console.readLine("New login (leave empty to keep): ") ?: return@withLibrarianConsole inputEnded()
    val password = console.readPassword("New password (leave empty to keep): ") ?: return@withLibrarianConsole inputEnded()
    val result = try {
        if (password.isNotEmpty()) {
            val confirmation = console.readPassword("Repeat new password: ") ?: return@withLibrarianConsole inputEnded()
            val matches = try {
                password.contentEquals(confirmation)
            } finally {
                confirmation.fill('\u0000')
            }
            if (!matches) return@withLibrarianConsole commandError("Passwords do not match.", 2)
        }
        DatabaseConnection.open(config).use { connection ->
            val update = UpdateLibrarian(
                PostgresAuthRepository(connection.database),
                BCryptPasswordHasher(),
                Clock.systemUTC(),
            )
            update.execute(login.ifEmpty { null }, password.takeIf { it.isNotEmpty() }?.concatToString())
        }
    } finally {
        password.fill('\u0000')
    }

    when (result) {
        UpdateLibrarianResult.UPDATED -> {
            println("Librarian credentials updated.")
            0
        }
        UpdateLibrarianResult.NO_CHANGES -> {
            println("No credentials changed.")
            0
        }
        UpdateLibrarianResult.NOT_FOUND -> commandError("No librarian exists. Run create-librarian first.", 3)
        UpdateLibrarianResult.LOGIN_IN_USE -> commandError("This login is already in use.", 3)
    }
}

private fun withLibrarianConsole(
    name: String,
    env: Map<String, String>,
    invalidInputMessage: String,
    command: (Console, DatabaseConfig) -> Int,
): Int {
    val console = System.console() ?: return commandError("$name requires an interactive terminal.", 2)
    val config = try {
        AppConfig.databaseFromEnvironment(env)
    } catch (error: IllegalStateException) {
        return commandError(error.message ?: "Invalid database configuration.", 2)
    }
    return try {
        command(console, config)
    } catch (error: AuthError) {
        if (error.code == AuthErrorCode.INVALID_DATA) commandError(invalidInputMessage, 2)
        else commandError("$name failed.", 1)
    } catch (_: IOError) {
        commandError("Could not read from the terminal.", 2)
    } catch (_: Exception) {
        commandError("$name failed. Check database connectivity and migrations.", 1)
    }
}

private fun inputEnded() = commandError("Input ended before all fields were read.", 2)

private fun commandError(message: String, exitCode: Int): Int {
    System.err.println(message)
    return exitCode
}
