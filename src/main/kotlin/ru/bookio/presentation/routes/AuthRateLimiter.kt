package ru.bookio.presentation.routes

import java.time.Clock
import java.time.Duration
import java.time.Instant

class AuthRateLimiter(
    private val clock: Clock,
    private val limit: Int = 30,
    private val globalLimit: Int = 120,
    private val window: Duration = Duration.ofMinutes(1),
) {
    private data class Request(val client: String, val time: Instant)

    private val requests = ArrayDeque<Request>()
    private val clientCounts = mutableMapOf<String, Int>()

    @Synchronized
    fun allow(client: String): Boolean {
        val now = clock.instant()
        val cutoff = now.minus(window)
        while (requests.isNotEmpty() && !requests.first().time.isAfter(cutoff)) {
            val expiredClient = requests.removeFirst().client
            val count = clientCounts.getValue(expiredClient)
            if (count == 1) clientCounts.remove(expiredClient) else clientCounts[expiredClient] = count - 1
        }

        val count = clientCounts.getOrDefault(client, 0)
        if (count >= limit || requests.size >= globalLimit) return false
        requests.addLast(Request(client, now))
        clientCounts[client] = count + 1
        return true
    }
}
