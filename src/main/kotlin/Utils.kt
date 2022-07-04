import com.soywiz.klock.DateTime
import com.soywiz.klock.DateTimeSpan
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.chat.members.unbanChatMember
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.toTelegramDate
import kotlinx.coroutines.delay
import java.time.Duration

object Utils {

    val User.fullName: String
        get() = "$firstName $lastName".trim()

    val User.detailName: String
        get() = "$fullName ${username?.let { "(${it.username})" }}[${id.chatId}]"

    context(BehaviourContext)
    suspend fun kickUser(chat: Chat, user: User, sendMessage: Boolean, banTime: DateTimeSpan? = null) {
        if (banChatMember(chat.id, user, banTime?.let { DateTime.now().plus(it).toTelegramDate() })) {
            banTime ?: unbanChatMember(chat.id, user)
            logger.info("Kick ${user.detailName} from chat ${chat.id.chatId}")
            if (sendMessage) sendMessage(chat, String.format(Constants.failVerification, user.fullName))
        } else {
            logger.warn("Failed to kick ${user.detailName} from chat ${chat.id.chatId}")
        }
    }
}

class Retry<T>(private val block: suspend () -> T) {

    private var exceptionHandler: (suspend (Throwable) -> Unit)? = null

    fun onFailure(handler: suspend (Throwable) -> Unit): Retry<T> {
        exceptionHandler = handler
        return this
    }

    suspend operator fun invoke(delayOnFailure: Duration): T {
        while (true) {
            runCatching {
                block()
            }.onSuccess {
                return it
            }.onFailure {
                exceptionHandler?.invoke(it)
            }
            delay(delayOnFailure.toMillis())
        }
    }
}

fun <T> retry(block: suspend () -> T) = Retry(block)
