package function

import Constants
import database.LogDao
import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.media.sendVisualMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommandWithArgs
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.utils.RiskFeature
import io.ktor.util.collections.*
import io.ktor.util.logging.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import logger
import org.jetbrains.exposed.sql.transactions.transaction
import plugin.ERNIEViLG
import util.BotUtils.detailName
import util.BotUtils.sendAutoDeleteMessage
import java.io.File
import kotlin.time.Duration

@Serializable
private class DrawConfig(
    val allowedGroups: Set<Long>
)

private class DrawTask(
    val user: User,
    val msg: Message,
    val qReply: Message,
    val type: String,
    val keyword: String
)

private enum class DrawLogMessage {
    QUEUE, CREATE, SUCCESS, FAILED, DELETED
}

private const val configFile = "data/draw/config.json"

private val pendingSet = ConcurrentSet<Long>()
private val channel = Channel<DrawTask>(Channel.BUFFERED)

private fun log(chat: Chat, user: User, message: DrawLogMessage) {
    transaction {
        LogDao.new {
            this.type = "DRAW"
            this.date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            this.chat = chat.id.chatId
            this.user = user.id.chatId
            this.admin = null
            this.level = "debug"
            this.message = message.name
        }
        logger.debug("Draw: user ${user.detailName} type $message")
    }
}

context(BehaviourContext)
@OptIn(RiskFeature::class)
suspend fun installDraw() {
    launch {
        for (task in channel) {
            log(task.msg.chat, task.user, DrawLogMessage.CREATE)
            runCatching { deleteMessage(task.qReply) }

            var files: Array<File>? = null
            var drawing: Message? = null
            try {
                drawing = reply(task.msg, Constants.drawingPicture)
                files = try {
                    ERNIEViLG.generate(task.type, task.keyword)
                } catch (e: IllegalArgumentException) {
                    sendAutoDeleteMessage(task.msg.chat, e.message!!, replyToMessageId = task.msg.messageId)
                    log(task.msg.chat, task.user, DrawLogMessage.FAILED)
                    continue
                }
                if (files.isNullOrEmpty()) {
                    sendAutoDeleteMessage(task.msg.chat, Constants.drawPictureFailed, replyToMessageId = task.msg.messageId)
                    log(task.msg.chat, task.user, DrawLogMessage.FAILED)
                    continue
                }
                val medias = sendVisualMediaGroup(
                    task.msg.chat,
                    files.map { TelegramMediaPhoto(InputFile(it)) },
                    replyToMessageId = task.msg.messageId
                )
                log(task.msg.chat, task.user, DrawLogMessage.SUCCESS)
                launch {
                    delay(Duration.parse("3m"))
                    medias.forEach {
                        runCatching { deleteMessage(it) }
                    }
                }
            } catch (_: ReplyMessageNotFoundException) {
                log(task.msg.chat, task.user, DrawLogMessage.DELETED)
            } catch (e: Exception) {
                logger.error("Unexpected exception happened when drawing", e)
                log(task.msg.chat, task.user, DrawLogMessage.FAILED)
            } finally {
                pendingSet.remove(task.user.id.chatId)
                drawing?.runCatching { deleteMessage(this) }
                files?.forEach(File::delete)
            }
        }
    }

    onCommandWithArgs("draw") { msg, args ->
        val user = msg.from ?: return@onCommandWithArgs
        val config = Json.decodeFromString<DrawConfig>(File(configFile).readText())
        if (msg.chat.id.chatId !in config.allowedGroups) {
            sendAutoDeleteMessage(msg.chat, Constants.groupNotInWhiteList, replyToMessageId = msg.messageId)
            return@onCommandWithArgs
        }
        if (args.size != 2) {
            sendAutoDeleteMessage(msg.chat, Constants.invalidCommand, replyToMessageId = msg.messageId)
            return@onCommandWithArgs
        }
        if (pendingSet.contains(user.id.chatId)) {
            sendAutoDeleteMessage(msg.chat, Constants.drawPending, replyToMessageId = msg.messageId)
            return@onCommandWithArgs
        }

        val qReply = reply(msg, Constants.drawQueueing)
        channel.trySend(DrawTask(user, msg, qReply, args[0], args[1]))
            .onSuccess {
                pendingSet.add(user.id.chatId)
                log(msg.chat, user, DrawLogMessage.QUEUE)
            }
            .onFailure {
                runCatching { deleteMessage(qReply) }
                sendAutoDeleteMessage(msg.chat, Constants.drawOverflow, replyToMessageId = msg.messageId)
            }
    }
}
