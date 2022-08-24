package plugin

import commonHttpClient
import config
import io.ktor.client.request.*
import io.ktor.client.statement.*

object Captcha {

    suspend fun createSession(easyMode: Boolean): String {
        val response = commonHttpClient
            .post(config.captchaApiUrl + "/service/create") {
                header("apiKey", config.captchaApiKey)
                header("isTagged", easyMode)
            }
        return response.bodyAsText()
    }

    suspend fun getVerifyResult(sessionId: String): String {
        val response = commonHttpClient.get(config.captchaApiUrl + "/service/result") {
            header("sessionId", sessionId)
        }
        return response.bodyAsText()
    }
}
