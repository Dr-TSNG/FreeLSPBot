import dev.inmo.tgbotapi.webapps.onViewportChanged
import dev.inmo.tgbotapi.webapps.webApp
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.html.dom.append
import kotlinx.html.id
import kotlinx.html.iframe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun onViewportChanged() {
    val element: dynamic = document.querySelector(".loader_container")
    element.style.height = webApp.viewportHeight.toString() + "px"
}

private suspend fun receiveTextOrClose(webSocket: DefaultWebSocketSession): String? {
    return when (val frame = webSocket.incoming.receive()) {
        is Frame.Text -> frame.readText()
        is Frame.Close -> {
            val reason = frame.readReason()
            if (reason?.knownReason == CloseReason.Codes.NORMAL) {
                webApp.close()
                null
            } else {
                throw IllegalStateException("WebSocket closed with reason: $reason")
            }
        }

        else -> throw IllegalStateException("Unexpected frame $frame")
    }
}

fun main() {
    console.log("Web app started")
    val client = HttpClient {
        install(WebSockets)
    }
    val baseUrl = window.location.pathname.substringBeforeLast('/')
    val token = window.location.search.substringAfter("?token=")

    window.onload = {
        onViewportChanged()
        webApp.onViewportChanged { onViewportChanged() }
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            runCatching {
                client.wss("$baseUrl/ws") {
                    console.log("Established WebSocket connection")
                    val wrapper = WebAppDataWrapper(webApp.initData, webApp.initDataUnsafe.hash, token)
                    outgoing.send(Frame.Text(Json.encodeToString(wrapper)))
                    val iframeUrl = receiveTextOrClose(this) ?: return@wss
                    document.querySelector(".loader_container")?.classList?.add("hidden")
                    document.getElementById("captcha_container")!!.append {
                        iframe {
                            id = "captcha"
                            src = iframeUrl
                        }
                    }
                    val closeReason = closeReason.await()
                    if (closeReason?.knownReason != CloseReason.Codes.TRY_AGAIN_LATER) {
                        webApp.close()
                    }
                }
            }.onFailure {
                console.error(it.stackTraceToString())
                window.alert(it.stackTraceToString())
            }
        }
    }
}
