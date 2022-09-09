package function

import Constants
import database.LogDao
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.media.sendVisualMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommandWithArgs
import dev.inmo.tgbotapi.extensions.utils.asPublicChat
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.utils.PreviewFeature
import dev.inmo.tgbotapi.utils.RiskFeature
import io.ktor.util.collections.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import plugin.ERNIEViLG
import util.BotUtils.sendAutoDeleteMessage
import java.io.File
import kotlin.time.Duration

@Serializable
private class DrawConfig(
    val coolDownTime: String,
    val allowedGroups: Set<Long>
)

private enum class DrawLogMessage {
    CREATE, SUCCESS, FAILED
}

private const val configFile = "data/draw/config.json"

private val groupLock = ConcurrentSet<Long>()

private fun log(chat: PublicChat?, user: User?, message: DrawLogMessage) {
    transaction {
        LogDao.new {
            this.type = "DRAW"
            this.date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            this.chat = chat?.id?.chatId ?: 0
            this.user = user?.id?.chatId ?: 0
            this.admin = null
            this.level = "debug"
            this.message = message.name
        }
    }
}

context(BehaviourContext)
@OptIn(PreviewFeature::class, RiskFeature::class)
suspend fun installDraw() {
    onCommandWithArgs("draw") { msg, args ->
        val config = Json.decodeFromString<DrawConfig>(File(configFile).readText())
        if (msg.chat.id.chatId !in config.allowedGroups) {
            sendAutoDeleteMessage(msg.chat, Constants.groupNotInWhiteList, replyToMessageId = msg.messageId)
            return@onCommandWithArgs
        }
        if (args.size != 2) {
            sendAutoDeleteMessage(msg.chat, Constants.invalidCommand, replyToMessageId = msg.messageId)
            return@onCommandWithArgs
        }

        if (groupLock.contains(msg.chat.id.chatId)) {
            sendAutoDeleteMessage(msg.chat, Constants.drawPending.format(config.coolDownTime), replyToMessageId = msg.messageId)
            return@onCommandWithArgs
        }
        launch {
            groupLock.add(msg.chat.id.chatId)
            delay(Duration.parse(config.coolDownTime))
            groupLock.remove(msg.chat.id.chatId)
        }

        launch {
            var files: Array<File>? = null
            try {
                val drawing = reply(msg, Constants.drawingPicture)
                log(msg.chat.asPublicChat(), msg.from, DrawLogMessage.CREATE)
                files = try {
                    ERNIEViLG.generate(type = args[0], keyword = args[1])
                } catch (e: IllegalArgumentException) {
                    sendAutoDeleteMessage(msg.chat, e.message!!)
                    return@launch
                } finally {
                    deleteMessage(drawing)
                }
                if (files.isNullOrEmpty()) {
                    sendAutoDeleteMessage(
                        msg.chat,
                        Constants.drawPictureFailed,
                        replyToMessageId = msg.messageId,
                        allowSendingWithoutReply = true
                    )
                    log(msg.chat.asPublicChat(), msg.from, DrawLogMessage.FAILED)
                    return@launch
                }
                val medias = sendVisualMediaGroup(
                    msg.chat,
                    files.map { TelegramMediaPhoto(InputFile(it)) },
                    replyToMessageId = msg.messageId,
                    allowSendingWithoutReply = true
                )
                log(msg.chat.asPublicChat(), msg.from, DrawLogMessage.SUCCESS)
                CoroutineScope(Dispatchers.Default).launch {
                    delay(Duration.parse("3m"))
                    medias.forEach {
                        runCatching { deleteMessage(it) }
                    }
                }
            } finally {
                files?.forEach(File::delete)
            }
        }
    }
}
