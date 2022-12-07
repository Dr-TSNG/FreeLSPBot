package function

import Messages
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommandWithArgs
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.reply_to_message
import dev.inmo.tgbotapi.types.message.abstracts.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import plugin.ChatGpt
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private val msgMap = ConcurrentHashMap<Long, ChatGpt.Context>()

context(BehaviourContext)
suspend fun installChatBot() {
    suspend fun dealContext(msg: Message, result: Result<ChatGpt.Context>) {
        result.onSuccess {
            val reply = reply(msg, it.text)
            msgMap[reply.messageId] = it
            CoroutineScope(Dispatchers.Default).launch {
                delay(Duration.parse("1h"))
                msgMap.remove(reply.messageId)
            }
        }.onFailure {
            reply(msg, Messages.gptError(it.message ?: "Unknown"))
        }
    }

    onCommandWithArgs("chat_gpt") { msg, args ->
        val ask = args.joinToString(" ")
        val result = ChatGpt.chat(ChatGpt.Context(ask, null, null))
        dealContext(msg, result)
    }

    onText { msg ->
        val replyMsg = msg.reply_to_message?.messageId ?: return@onText
        val lastContext = msgMap[replyMsg] ?: return@onText
        val result = ChatGpt.chat(lastContext.copy(text = msg.content.text))
        dealContext(msg, result)
    }
}
