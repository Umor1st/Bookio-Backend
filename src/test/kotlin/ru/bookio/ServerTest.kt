package ru.bookio

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.bookio.presentation.routes.healthRoutes

class ServerTest {
    @Test
    fun healthResponds() = testApplication {
        application { healthRoutes() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }
}
