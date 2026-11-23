package dev.gitpockit.core.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * "Sign in with GitHub" via the OAuth device flow: the app shows a short code, the user enters it
 * at github.com/login/device, and the app receives a token. Needs only the OAuth App's public
 * client id (no client secret), which is why it suits open-source mobile apps.
 */
class DeviceFlow(
    engine: HttpClientEngine,
    private val clientId: String,
    private val webUrl: String = "https://github.com",
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(engine) { expectSuccess = false }

    /** Scopes: private repos (code, issues, PRs), Actions workflows, and reading the profile. */
    suspend fun start(scope: String = "repo workflow read:user"): DeviceCode {
        val text = post("$webUrl/login/device/code") {
            append("client_id", clientId)
            append("scope", scope)
        }
        val error = runCatching { json.decodeFromString<TokenResponse>(text).error }.getOrNull()
        if (error != null) throw GitHubException("GitHub refused the sign-in request ($error). Is device flow enabled for the OAuth app?")
        return json.decodeFromString(text)
    }

    /**
     * Polls until the user approves (returns the token), denies, or the code expires.
     * Follows GitHub's `interval` and `slow_down` rules.
     */
    suspend fun awaitToken(code: DeviceCode): String {
        var interval = code.interval.coerceAtLeast(1).toLong()
        var waited = 0L
        while (waited < code.expiresIn) {
            sleep(interval * 1000)
            waited += interval
            val text = post("$webUrl/login/oauth/access_token") {
                append("client_id", clientId)
                append("device_code", code.deviceCode)
                append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            }
            val r = json.decodeFromString<TokenResponse>(text)
            when {
                r.accessToken != null -> return r.accessToken
                r.error == "authorization_pending" -> Unit
                r.error == "slow_down" -> interval = r.interval?.toLong() ?: (interval + 5)
                r.error == "expired_token" -> throw GitHubException("The code expired. Please try again.")
                r.error == "access_denied" -> throw GitHubException("Sign-in was cancelled on GitHub.")
                else -> throw GitHubException("GitHub sign-in failed: ${r.errorDescription ?: r.error}")
            }
        }
        throw GitHubException("The code expired. Please try again.")
    }

    override fun close() = http.close()

    private suspend fun post(url: String, form: io.ktor.http.ParametersBuilder.() -> Unit): String = try {
        http.submitForm(url, parameters(form)) { header("Accept", "application/json") }.bodyAsText()
    } catch (e: IOException) {
        throw GitHubNetworkException(e)
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
        val interval: Int? = null,
    )
}
