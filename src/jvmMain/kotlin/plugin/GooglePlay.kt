package plugin

import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.network.IHttpClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import logger
import proxiedHttpClient
import util.retry
import java.io.File
import java.time.Duration

@Serializable
private class Api(val email: String, val token: String)

private object GHttpClient : IHttpClient {

    override fun get(url: String, headers: Map<String, String>): PlayResponse {
        return get(url, headers, mapOf())
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val response = runBlocking {
            proxiedHttpClient.get(url) {
                headers.forEach(::header)
                params.forEach(::parameter)
            }
        }
        return buildPlayResponse(response)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse {
        val response = runBlocking {
            proxiedHttpClient.get(url + paramString) {
                headers.forEach(::header)
            }
        }
        return buildPlayResponse(response)
    }

    override fun getAuth(url: String): PlayResponse {
        return PlayResponse().apply {
            isSuccessful = false
            code = 444
        }
    }

    override fun postAuth(url: String, body: ByteArray): PlayResponse {
        return PlayResponse().apply {
            isSuccessful = false
            code = 444
        }
    }

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): PlayResponse {
        val response = runBlocking {
            proxiedHttpClient.post(url) {
                headers.forEach(::header)
                header(HttpHeaders.ContentType, "application/x-protobuf")
                setBody(body)
            }
        }
        return buildPlayResponse(response)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val response = runBlocking {
            proxiedHttpClient.post(url) {
                headers.forEach(::header)
                params.forEach(::parameter)
            }
        }
        return buildPlayResponse(response)
    }

    @JvmStatic
    private fun buildPlayResponse(response: HttpResponse): PlayResponse {
        return PlayResponse().apply {
            runBlocking {
                code = response.status.value
                if (response.status == HttpStatusCode.OK) {
                    isSuccessful = true
                    responseBytes = response.body()
                } else {
                    isSuccessful = false
                    errorBytes = response.readBytes()
                    errorString = String(errorBytes)
                }
            }
        }
    }
}

class GooglePlay private constructor(private var authData: AuthData) {
    companion object {
        fun create() = runCatching {
            GooglePlay(login())
        }

        private fun login(): AuthData {
            val api = Json.decodeFromString<Api>(File("data/googleplay/api.json").readText())
            return AuthHelper.using(GHttpClient).build(api.email, api.token, "device.properties")
        }
    }

    suspend fun getAppInfo(packageName: String) = runCatching {
        retry {
            AppDetailsHelper(authData).using(GHttpClient).getAppByPackageName(packageName)
        }.onFailure {
            logger.info("Failed to get app info, maybe the token is expired")
            authData = login()
        }.invoke(Duration.ofSeconds(1))
    }

    suspend fun downloadApp(app: App) = runCatching {
        retry {
            PurchaseHelper(authData).using(GHttpClient).purchase(app.packageName, app.versionCode, app.offerType)
        }.onFailure {
            logger.info("Failed to get app info, maybe the token is expired")
            authData = login()
        }.invoke(Duration.ofSeconds(1))
    }
}
