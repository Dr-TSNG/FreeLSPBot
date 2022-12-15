package function

import Messages
import database.ChatGptDao
import database.ChatGptTable
import database.JoinRequestDao
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommandWithArgs
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.reply_to_message
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.message.abstracts.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.transaction
import plugin.ChatGpt
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private val msgMap = ConcurrentHashMap<Long, ChatGpt.Conversation>()

context(BehaviourContext)
suspend fun installChatBot() {
    suspend fun enforceInGroup(msg: Message): Boolean {
        if (msg.chat !is GroupChat) {
            reply(msg, Messages.cmdGroupOnly)
            return false
        }
        val dao = transaction { JoinRequestDao.findById(msg.chat.id.chatId) }
        if (dao == null) {
            reply(msg, Messages.cmdGroupNotInWhiteList)
            return false
        }
        return true
    }

    suspend fun dealContext(msg: Message, result: Result<ChatGpt.Conversation>) {
        result.onSuccess {
            val reply = reply(msg, it.reply)
            msgMap[reply.messageId] = it
            CoroutineScope(Dispatchers.Default).launch {
                delay(Duration.parse("1h"))
                msgMap.remove(reply.messageId)
            }
        }.onFailure {
            reply(msg, Messages.gptError(it.message ?: "Unknown"))
        }
    }

    onCommandWithArgs("gpt_chat") { msg, args ->
        val validUser = transaction { ChatGptDao.find { ChatGptTable.user eq msg.from!!.id.chatId }.count() > 0 }
        val hasContribution = msg.from != null && validUser
        if (!hasContribution && !enforceInGroup(msg)) return@onCommandWithArgs
        if (args.isEmpty()) {
            reply(msg, Messages.cmdIllegalArgument)
            return@onCommandWithArgs
        }
        val ask = args.joinToString(" ")
        val result = ChatGpt.chat(ChatGpt.Conversation().ask(ask))
        dealContext(msg, result)
    }

    onCommand("gpt_tokens") { msg ->
        val user = msg.from ?: return@onCommand
        val reply = transaction {
            val all = ChatGptDao.all()
            val total = all.count()
            val valid = all.count { !it.expired }
            val yours = all.count { it.user == user.id.chatId }
            val yoursValid = all.count { !it.expired && it.user == user.id.chatId }
            Messages.gptTokens(total, valid, yours, yoursValid)
        }
        reply(msg, reply)
    }

    onCommand("gpt_statistics") { msg ->
        val user = msg.from ?: return@onCommand
        val (total, yours) = transaction {
            val total = ChatGptDao.all().sumOf { it.used }
            val yours = ChatGptDao.find { ChatGptTable.user eq user.id.chatId }.sumOf { it.used }
            total to yours
        }
        reply(msg, Messages.gptStatistics(total, yours))
    }

    onCommandWithArgs("gpt_addtoken") { msg, args ->
        val user = msg.from ?: return@onCommandWithArgs
        if (args.size != 1) {
            reply(msg, Messages.cmdIllegalArgument)
            return@onCommandWithArgs
        }
        ChatGpt.testToken(args[0]).onFailure {
            reply(msg, Messages.gptError(it.message ?: "Unknown"))
            return@onCommandWithArgs
        }
        transaction {
            ChatGptDao.new {
                this.user = user.id.chatId
                this.token = args[0]
            }
        }
        reply(msg, Messages.gptAddedToken)
    }

    onCommand("gpt_cleartoken") { msg ->
        val user = msg.from ?: return@onCommand
        transaction {
            ChatGptDao.find { ChatGptTable.user eq user.id.chatId }.forEach { it.delete() }
        }
        reply(msg, Messages.gptClearedToken)
    }

    onText { msg ->
        val replyMsg = msg.reply_to_message?.messageId ?: return@onText
        val conversation = msgMap[replyMsg] ?: return@onText
        val result = ChatGpt.chat(conversation.ask(msg.content.text))
        dealContext(msg, result)
    }
}
