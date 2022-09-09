package util

import com.soywiz.klock.DateTime
import com.soywiz.klock.TimeSpan
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.chat.get.getChatAdministrators
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.unbanChatMember
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.utils.asWithOptionalLanguageCode
import dev.inmo.tgbotapi.types.MessageIdentifier
import dev.inmo.tgbotapi.types.buttons.KeyboardMarkup
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.chat.member.AdministratorChatMember
import dev.inmo.tgbotapi.types.message.ParseMode
import dev.inmo.tgbotapi.types.message.textsources.mention
import dev.inmo.tgbotapi.types.toTelegramDate
import dev.inmo.tgbotapi.utils.PreviewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logger
import kotlin.time.Duration
import kotlin.time.DurationUnit

object BotUtils {

    val User.fullNameMention: String
        get() {
            val combineName = "$firstName $lastName".trim()
            return if (username == null) combineName
            else mention(combineName, this).markdownV2
        }

    val User.detailName: String
        get() = buildString {
            append("$firstName $lastName".trim())
            username?.let { append(" ${it.username}") }
            append(" [${id.chatId}]")
        }

    @OptIn(PreviewFeature::class)
    val User.isChinese: Boolean
        get() = asWithOptionalLanguageCode()?.languageCode?.startsWith("zh") ?: false

    val PublicChat.detailName: String
        get() = "$title [${id.chatId}]"

    suspend fun TelegramBot.kickUser(chat: Chat, user: User, ban: String?) {
        val time = ban?.let {
            val ms = Duration.parse(it).toDouble(DurationUnit.MILLISECONDS)
            DateTime.now().plus(TimeSpan(ms)).toTelegramDate()
        }
        if (banChatMember(chat.id, user, time)) {
            if (ban == null) {
                unbanChatMember(chat.id, user)
                logger.info("Kick user ${user.detailName} from chat ${chat.id.chatId}")
            } else {
                logger.info("Ban user ${user.detailName} from chat ${chat.id.chatId} for $ban")
            }
        } else {
            logger.warn("Failed to kick user ${user.detailName} from chat ${chat.id.chatId}")
        }
    }

    suspend fun TelegramBot.getGroupAdmin(
        chat: PublicChat,
        user: User?,
        filter: (AdministratorChatMember) -> Boolean = { true }
    ): AdministratorChatMember? {
        if (user == null) return null
        val admins = getChatAdministrators(chat)
        return admins.find { it.user.id.chatId == user.id.chatId && filter(it) }
    }

    suspend fun TelegramBot.sendAutoDeleteMessage(
        chat: Chat,
        text: String,
        parseMode: ParseMode? = null,
        disableWebPagePreview: Boolean? = null,
        disableNotification: Boolean = false,
        protectContent: Boolean = false,
        replyToMessageId: MessageIdentifier? = null,
        allowSendingWithoutReply: Boolean? = null,
        replyMarkup: KeyboardMarkup? = null
    ) {
        val msg = sendMessage(
            chat, text, parseMode,
            disableWebPagePreview, disableNotification,
            protectContent, replyToMessageId, allowSendingWithoutReply, replyMarkup
        )
        CoroutineScope(Dispatchers.Default).launch {
            delay(Duration.parse("1m"))
            deleteMessage(msg)
        }
    }
}
