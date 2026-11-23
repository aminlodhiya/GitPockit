package dev.gitpockit.core.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFlowTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val code = DeviceCode("dev123", "ABCD-1234", "https://github.com/login/device", expiresIn = 900, interval = 5)

    @Test fun startsAndPollsWithSlowDown() = runTest {
        val replies = ArrayDeque(
            listOf(
                """{"error":"authorization_pending"}""",
                """{"error":"slow_down","interval":10}""",
                """{"access_token":"gho_token","token_type":"bearer"}""",
            ),
        )
        val bodies = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val engine = MockEngine { req ->
            bodies += req.body.toByteArray().decodeToString()
            if (req.url.encodedPath == "/login/device/code") {
                respond("""{"device_code":"dev123","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""", HttpStatusCode.OK, headers)
            } else {
                respond(replies.removeFirst(), HttpStatusCode.OK, headers)
            }
        }
        val flow = DeviceFlow(engine, clientId = "Iv1.client", sleep = { waits += it })
        val started = flow.start()
        assertEquals("ABCD-1234", started.userCode)
        assertTrue(bodies[0], bodies[0].contains("client_id=Iv1.client") && bodies[0].contains("scope=repo+workflow+read%3Auser"))

        assertEquals("gho_token", flow.awaitToken(started))
        assertEquals(listOf(5000L, 5000L, 10000L), waits) // slow_down raised the interval
        assertTrue(bodies.last().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
    }

    @Test fun deniedAndExpiredAreReported() = runTest {
        for ((error, text) in listOf("access_denied" to "cancelled", "expired_token" to "expired")) {
            val engine = MockEngine { respond("""{"error":"$error"}""", HttpStatusCode.OK, headers) }
            val e = runCatching { DeviceFlow(engine, "id", sleep = {}).awaitToken(code) }.exceptionOrNull()
            assertTrue("$error -> ${e?.message}", e is GitHubException && e.message!!.contains(text))
        }
    }

    @Test fun startFailsClearlyWhenDeviceFlowIsDisabled() = runTest {
        val engine = MockEngine { respond("""{"error":"device_flow_disabled"}""", HttpStatusCode.OK, headers) }
        val e = runCatching { DeviceFlow(engine, "id").start() }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e is GitHubException && e.message!!.contains("device flow enabled"))
    }
}
