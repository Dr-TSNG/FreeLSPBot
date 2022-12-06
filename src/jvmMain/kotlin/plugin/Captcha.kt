package plugin

import httpClient
import config
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

object Captcha {

    suspend fun createSession(easyMode: Boolean): String {
        val response = httpClient
            .post(config.captchaApiUrl + "/service/create") {
                header("apiKey", config.captchaApiKey)
                header("isTagged", easyMode)
            }
        return response.bodyAsText()
    }

    suspend fun getVerifyResult(sessionId: String): String? {
        val response = httpClient.get(config.captchaApiUrl + "/service/result") {
            header("sessionId", sessionId)
        }
        return if (response.status == HttpStatusCode.Forbidden) null
        else response.bodyAsText()
    }
}
