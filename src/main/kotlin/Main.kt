import Utils.kickUser
import com.soywiz.klock.DateTimeSpan
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.buildBot
import dev.inmo.tgbotapi.extensions.api.chat.members.restrictChatMember
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.media.editMessageMedia
import dev.inmo.tgbotapi.extensions.api.send.media.sendPhoto
import dev.inmo.tgbotapi.extensions.api.send.polls.sendQuizPoll
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onLeftChatMember
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.message
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.chat.LeftRestrictionsChatPermissions
import dev.inmo.tgbotapi.types.chat.RestrictionsChatPermissions
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import function.CS408
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.time.Duration

val config = Json.decodeFromString<Config>(File("data/config.json").readText())

val logger = KotlinLogging.logger {}

suspend fun main() {
    val bot = buildBot(config.token) {
        ktorClientEngineFactory = OkHttp
        proxy = ProxyBuilder.socks(config.proxyHost, config.proxyPort)
    }

    logger.info("Bot start. WhiteList: ${config.groupWhiteList}")

    bot.buildBehaviourWithLongPolling {
        logger.info(getMe().toString())
        val dataCallbackMap = mutableMapOf<Long, Pair<Long, suspend (DataCallbackQuery) -> Unit>>()

        onCommand("start") {
            sendMessage(it.chat, Constants.help)
        }

        onCommand("help") {
            sendMessage(it.chat, Constants.help)
        }

        onCommand("rq") {
            logger.debug("/rq command from {${it.chat.id.chatId}}")
            val (photo, ans) = CS408.pickUp()
            sendPhoto(it.chat, InputFile.fromFile(photo))
            sendQuizPoll(it.chat, "选择正确的选项", listOf("A", "B", "C", "D"), ans[0] - 'A')
        }

        onNewChatMembers(
            initialFilter = { config.groupWhiteList.contains(it.chat.id.chatId) }
        ) { message ->
            message.chatEvent.members.forEach { user ->
                logger.info { "New member ${Utils.parseUser(user)} in chat ${message.chat.id.chatId}" }
                var (photo, ans) = CS408.pickUp()
                if (restrictChatMember(message.chat.id, user, permissions = RestrictionsChatPermissions)) {
                    logger.info { "Restrict " + Utils.parseUser(user) }
                } else {
                    logger.debug { "No restrict permission in chat ${message.chat.id.chatId}" }
                    return@onNewChatMembers
                }

                var changesLeft = 3
                val verifier = sendPhoto(
                    message.chat,
                    InputFile.fromFile(photo),
                    String.format(Constants.newMemberReply, "${user.firstName} ${user.lastName}"),
                    replyMarkup = CS408.replyMarkup
                )
                val timeoutListener = launch {
                    delay(Duration.ofMinutes(3))
                    dataCallbackMap.remove(verifier.messageId)
                    kickUser(message.chat, user, true)
                    deleteMessage(verifier)
                }
                dataCallbackMap[verifier.messageId] = user.id.chatId to {
                    logger.debug { "Answer from ${Utils.parseUser(user)}: ${it.data}" }
                    when (it.data) {
                        Constants.changeQuestion -> {
                            if (changesLeft > 0) {
                                changesLeft--
                                val (newPhoto, newAns) = CS408.pickUp()
                                photo = newPhoto
                                ans = newAns
                                editMessageMedia(verifier, TelegramMediaPhoto(InputFile.fromFile(photo)))
                            }
                        }
                        ans -> {
                            logger.info("Verified ${Utils.parseUser(user)}")
                            timeoutListener.cancel()
                            sendMessage(message.chat, String.format(Constants.passVerification, user.firstName, user.lastName))
                            restrictChatMember(message.chat.id, user, permissions = LeftRestrictionsChatPermissions)
                            deleteMessage(verifier)
                        }
                        else -> {
                            logger.info("Wrong answer from ${Utils.parseUser(user)}")
                            timeoutListener.cancel()
                            kickUser(message.chat, user, true, DateTimeSpan(seconds = 60))
                            deleteMessage(verifier)
                        }
                    }
                }
            }
        }

        onLeftChatMember {
            logger.info { "Left member ${Utils.parseUser(it.chatEvent.user)} in chat ${it.chat.id.chatId}" }
        }

        onDataCallbackQuery(
            initialFilter = {
                val msg = it.message
                msg != null && it.from.id.chatId == dataCallbackMap[msg.messageId]?.first && config.groupWhiteList.contains(msg.chat.id.chatId)
            }
        ) {
            logger.debug("Inline message id data callback query from ${Utils.parseUser(it.from)}")
            dataCallbackMap[it.message!!.messageId]?.second?.invoke(it)
        }
    }.join()
}
