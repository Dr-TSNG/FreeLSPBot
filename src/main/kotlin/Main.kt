import Utils.detailName
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.buildBot
import dev.inmo.tgbotapi.extensions.api.chat.members.restrictChatMember
import dev.inmo.tgbotapi.extensions.api.send.media.sendPhoto
import dev.inmo.tgbotapi.extensions.api.send.polls.sendQuizPoll
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onLeftChatMember
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onNewChatMembers
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.chat.RestrictionsChatPermissions
import function.CS408
import function.NewChatMemberVerification
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.net.SocketException

val config = Json.decodeFromString<Config>(File("data/config.json").readText())

val logger = KotlinLogging.logger {}

suspend fun main() {
    val bot = buildBot(config.token) {
        ktorClientEngineFactory = OkHttp
        proxy = ProxyBuilder.socks(config.proxyHost, config.proxyPort)
    }

    logger.info("Bot start. WhiteList: ${config.groupWhiteList}")

    bot.buildBehaviourWithLongPolling(
        defaultExceptionsHandler = { e ->
            val ignore = listOf(
                CancellationException::class,
                HttpRequestTimeoutException::class,
                SocketException::class
            )
            if (!ignore.stream().anyMatch { it.isInstance(e) }) {
                logger.error("Exception happened!", e)
            }
        }
    ) {
        logger.info(getMe().toString())

        onCommand("start") {
            sendMessage(it.chat, Constants.help)
        }

        onCommand("rq") {
            logger.debug("/rq command from {${it.chat.id.chatId}}")
            val (photo, ans) = CS408.pickUp()
            sendPhoto(it.chat, InputFile.fromFile(photo))
            sendQuizPoll(it.chat, "选择正确的选项", listOf("A", "B", "C", "D"), ans[0] - 'A', false)
        }

        onCommand("rqa") {
            logger.debug("/rqa command from {${it.chat.id.chatId}}")
            val (photo, ans) = CS408.pickUp()
            sendPhoto(it.chat, InputFile.fromFile(photo))
            sendQuizPoll(it.chat, "选择正确的选项", listOf("A", "B", "C", "D"), ans[0] - 'A', true)
        }

        onNewChatMembers(
            initialFilter = { config.groupWhiteList.contains(it.chat.id.chatId) }
        ) { message ->
            message.chatEvent.members.forEach { user ->
                logger.info("New member ${user.detailName} in chat ${message.chat.id.chatId}")
                if (restrictChatMember(message.chat.id, user, permissions = RestrictionsChatPermissions)) {
                    logger.info("Restrict " + user.detailName)
                } else {
                    logger.debug("No restrict permission in chat ${message.chat.id.chatId}")
                    return@onNewChatMembers
                }
                NewChatMemberVerification.create(message.chat, user, 3)
            }
        }

        onLeftChatMember {
            logger.info("Left member ${it.chatEvent.user.detailName} in chat ${it.chat.id.chatId}")
        }

        onDataCallbackQuery(
            initialFilter = {
                NewChatMemberVerification.filter(it)
            }
        ) {
            logger.debug("Inline message id data callback query from ${it.from.detailName}")
            NewChatMemberVerification.listen(it)
        }
    }.join()
}
