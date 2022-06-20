import com.soywiz.klock.DateTime
import com.soywiz.klock.DateTimeSpan
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.unbanChatMember
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.toTelegramDate

object Utils {

    fun parseUser(user: User) =
        "${user.firstName} ${user.lastName}${user.username?.let { " (${it.username})" }}[${user.id.chatId}]"

    suspend fun BehaviourContext.kickUser(chat: Chat, user: User, sendMessage: Boolean, banTime: DateTimeSpan? = null) {
        if (banChatMember(chat.id, user, banTime?.let { DateTime.now().plus(it).toTelegramDate() })) {
            banTime ?: unbanChatMember(chat.id, user)
            logger.info("Kick ${parseUser(user)} from chat ${chat.id.chatId}")
            if (sendMessage) sendMessage(chat, String.format(Constants.failVerification, user.firstName, user.lastName))
        } else {
            logger.warn("Failed to kick ${parseUser(user)} from chat ${chat.id.chatId}")
        }
    }
}
