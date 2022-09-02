package util

import com.soywiz.klock.DateTime
import com.soywiz.klock.TimeSpan
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.chat.get.getChatAdministrators
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.unbanChatMember
import dev.inmo.tgbotapi.extensions.utils.asWithOptionalLanguageCode
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.chat.member.AdministratorChatMember
import dev.inmo.tgbotapi.types.toTelegramDate
import dev.inmo.tgbotapi.utils.PreviewFeature
import logger
import kotlin.time.Duration
import kotlin.time.DurationUnit

object BotUtils {

    val User.fullName: String
        get() {
            val combineName = "$firstName $lastName".trim()
            return if (username == null) combineName
            else "[$combineName](${username!!.username})"
        }

    val User.detailName: String
        get() = "$fullName [${id.chatId}]"

    @OptIn(PreviewFeature::class)
    val User.isChinese: Boolean
        get() = asWithOptionalLanguageCode()?.languageCode?.startsWith("zh") ?: false

    val PublicChat.detailName: String
        get() = "$title [${id.chatId}]"

    suspend fun TelegramBot.kickUser(chat: Chat, user: User, ban: String?) {
        val time = ban?.let {
            Duration.parse(ban).toDouble(DurationUnit.MILLISECONDS)
        }
        if (banChatMember(chat.id, user, time?.let { DateTime.now().plus(TimeSpan(it)).toTelegramDate() })) {
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
}
