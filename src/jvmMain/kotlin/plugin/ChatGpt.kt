package plugin

import database.ChatGptDao
import database.ChatGptTable
import httpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import logger
import org.jetbrains.exposed.sql.Random
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.IOException

object ChatGpt {

    private const val API_URL = "https://api.openai.com/v1/completions"

    private val formatter = Json {
        encodeDefaults = true
    }

    @JvmInline
    value class Conversation(val context: String = "") {
        val reply get() = context.split("\n\n").last().substringAfter("A: ")
        fun ask(prompt: String) = Conversation("$context\n\nQ: $prompt\n\nA: ")

        fun answer(answer: String) = Conversation("$context$answer")
    }

    @Serializable
    data class ChatRequest(
        val prompt: String,
        val model: String = "text-davinci-003",
        val temperature: Double = 0.0,
        @SerialName("max_tokens") val maxTokens: Int = 2048
    )

    suspend fun chat(conversation: Conversation): Result<Conversation> = runCatching {
        logger.debug("New gpt chat")
        val dao = transaction {
            ChatGptDao.wrapRow(ChatGptTable.selectAll().orderBy(Random()).limit(1).first())
        }
        val resp = httpClient.post(API_URL) {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer " + dao.token)
            setBody(formatter.encodeToString(ChatRequest(conversation.context)))
        }

        if (resp.status != HttpStatusCode.OK) {
            throw IOException("OpenAI API returned ${resp.status}")
        }

        val json = Json.parseToJsonElement(resp.bodyAsText())
        val text = json.jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: throw IOException("OpenAI API returned invalid response")

        transaction { dao.used++ }
        conversation.answer(text)
    }
}
