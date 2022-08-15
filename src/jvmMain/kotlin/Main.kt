import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.telegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.flushAccumulatedUpdates
import dev.inmo.tgbotapi.types.chat.ExtendedBot
import dev.inmo.tgbotapi.utils.TelegramAPIUrlsKeeper
import function.configureJoinRequestRouting
import function.installCS408
import function.installJoinRequestVerification
import function.installTwiFucker
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.network.sockets.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.net.SocketException

val config = Json.decodeFromString<Config>(File("data/config.json").readText())

val commonHttpClient = HttpClient(OkHttp)
val proxiedHttpClient = HttpClient(OkHttp) {
    engine {
        proxy = ProxyBuilder.socks(config.proxyHost, config.proxyPort)
    }
}

val logger = KotlinLogging.logger {}
lateinit var botSelf: ExtendedBot

suspend fun main() {
    val telegramBotAPIUrlsKeeper = TelegramAPIUrlsKeeper(config.token, config.botApiUrl)
    val bot = telegramBot(telegramBotAPIUrlsKeeper)

    logger.info("Bot start. WhiteList: ${config.groupWhiteList}")

    embeddedServer(Netty, port = config.serverPort, host = config.serverHost) {
        routing {
            configureJoinRequestRouting(telegramBotAPIUrlsKeeper, bot)
        }
    }.start(wait = false)

    bot.flushAccumulatedUpdates()
    bot.buildBehaviourWithLongPolling(
        defaultExceptionsHandler = { e ->
            val ignore = listOf(
                CancellationException::class,
                HttpRequestTimeoutException::class,
                SocketException::class,
                SocketTimeoutException::class
            )
            if (!ignore.stream().anyMatch { it.isInstance(e) }) {
                logger.error("Exception happened!", e)
            }
        }
    ) {
        botSelf = getMe()
        logger.info(botSelf.toString())

        onCommand("start") {
            sendMessage(it.chat, Constants.help)
        }

        installCS408()
        installTwiFucker()
        installJoinRequestVerification()
    }.join()
}
