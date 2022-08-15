import dev.inmo.micro_utils.coroutines.launchSafelyWithoutExceptions
import dev.inmo.tgbotapi.webapps.haptic.HapticFeedbackType
import dev.inmo.tgbotapi.webapps.onClick
import dev.inmo.tgbotapi.webapps.webApp
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.html.dom.append
import kotlinx.html.id
import kotlinx.html.iframe
import kotlinx.html.p
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun main() {
    console.log("Web app started")
    val client = HttpClient()
    val lang = window.navigator.language
    val isChinese = lang.startsWith("zh-")
    val baseUrl = window.location.pathname.substringBeforeLast('/')
    val token = window.location.search.substringAfter("?token=")

    window.onload = {
        val scope = CoroutineScope(Dispatchers.Default)
        runCatching {
            scope.launchSafelyWithoutExceptions {
                val response = client.post("$baseUrl/load") {
                    val wrapper = WebAppDataWrapper(webApp.initData, webApp.initDataUnsafe.hash, token)
                    setBody(Json.encodeToString(wrapper))
                }
                if (response.status != HttpStatusCode.OK) {
                    document.body!!.append {
                        p { +"Error: ${response.status}" }
                    }
                    return@launchSafelyWithoutExceptions
                }
                val sessionId = response.bodyAsText()
                document.getElementById("captcha_container")!!.append {
                    iframe {
                        id = "captcha"
                        src = "https://api.nullptr.icu/captcha/?sessionId=$sessionId"
                    }
                }
                webApp.mainButton.show()
            }

            with(webApp) {
                mainButton.let {
                    it.setText(if (isChinese) "完成" else "Finish")
                    it.onClick {
                        scope.launchSafelyWithoutExceptions {
                            client.post("$baseUrl/complete") {
                                val wrapper = WebAppDataWrapper(webApp.initData, webApp.initDataUnsafe.hash, token)
                                setBody(Json.encodeToString(wrapper))
                            }
                            hapticFeedback.notificationOccurred(HapticFeedbackType.Success)
                            close()
                        }
                    }
                }
            }
        }.onFailure {
            window.alert(it.stackTraceToString())
        }
    }
}
