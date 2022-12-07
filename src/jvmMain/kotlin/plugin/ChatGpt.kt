package plugin

import com.benasher44.uuid.uuid4
import httpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import logger
import java.io.File
import java.io.IOException

object ChatGpt {

    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36 Edg/108.0.1462.42"
    private const val SESSION_PREFIX = "__Secure-next-auth.session-token="
    private const val SESSION_URL = "https://chat.openai.com/api/auth/session"
    private const val CONVERSATION_URL = "https://chat.openai.com/backend-api/conversation"

    private val keyFile = File("data/ChatGpt/apiKey")
    private val uuid = uuid4().toString()
    private val mutex = Mutex()
    private val formatter = Json {
        encodeDefaults = true
        @OptIn(ExperimentalSerializationApi::class)
        explicitNulls = false
    }

    private var accessToken: String? = null

    data class Context(
        val text: String,
        val conversationId: String?,
        val parentMessageId: String?
    )

    @Serializable
    private data class Content(
        @SerialName("content_type")
        val contentType: String,
        val parts: List<String>
    )

    @Serializable
    private data class Message(
        val id: String = uuid,
        val role: String = "user",
        val content: Content
    )

    @Serializable
    private data class Conversation(
        val action: String = "next",
        val model: String = "text-davinci-002-render",
        val messages: List<Message>,
        @SerialName("conversation_id")
        val conversationId: String?,
        @SerialName("parent_message_id")
        val parentMessageId: String
    )

    private suspend fun createSession(): Boolean {
        mutex.withLock {
            if (accessToken != null) return true
            val resp = httpClient.get(SESSION_URL) {
                header("User-Agent", UA)
                header("Cookie", SESSION_PREFIX + keyFile.readText())
            }
            val cookies = resp.headers.getAll("Set-Cookie")
            val json = Json.parseToJsonElement(resp.bodyAsText())
            val sessionToken = cookies?.find { it.startsWith(SESSION_PREFIX) }?.substringAfter('=')
            if (sessionToken == null) {
                logger.error("Failed to get session token")
                return false
            }
            keyFile.writeText(sessionToken)
            accessToken = json.jsonObject["accessToken"]?.jsonPrimitive?.content
            if (accessToken == null) {
                logger.error("Failed to get access token")
                return false
            }
            logger.info("Updated session token")
        }
        return true
    }

    suspend fun chat(context: Context): Result<Context> = runCatching {
        if (accessToken == null && !createSession()) throw IOException("Failed to create socket")

        val resp = httpClient.post(CONVERSATION_URL) {
            header("User-Agent", UA)
            header("Accept", "application/json")
            header("Content-Type", "application/json")
            header("Authorization", "Bearer $accessToken")
            val data = Conversation(
                messages = listOf(Message(content = Content("text", listOf(context.text)))),
                conversationId = context.conversationId,
                parentMessageId = context.parentMessageId ?: uuid4().toString()
            )
            setBody(formatter.encodeToString(data))
        }
        if (!resp.status.isSuccess()) throw IOException(resp.status.description)

        val data = resp.bodyAsText().split('\n')
        val json = Json.parseToJsonElement(data[data.size - 5].substringAfter("data: "))
        val message = json.jsonObject["message"]?.jsonObject
        val text = message?.get("content")?.jsonObject?.get("parts")?.jsonArray?.get(0)?.jsonPrimitive?.content
        val conversationId = message?.get("conversation_id")?.jsonPrimitive?.content
        val parentMessageId = message?.get("id")?.jsonPrimitive?.content
        if (text == null) throw IOException("Response text is null")
        Context(text, conversationId, parentMessageId)
    }
}
