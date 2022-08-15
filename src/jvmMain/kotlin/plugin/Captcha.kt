package plugin

import commonHttpClient
import config
import io.ktor.client.request.*
import io.ktor.client.statement.*

object Captcha {

    suspend fun createSession(): String {
        val response = commonHttpClient.get(config.captchaApiUrl + "/service/create")
        return response.bodyAsText()
    }

    suspend fun getVerifyResult(sessionId: String): String {
        val response = commonHttpClient.get(config.captchaApiUrl + "/service/result") {
            header("sessionId", sessionId)
        }
        return response.bodyAsText()
    }
}
