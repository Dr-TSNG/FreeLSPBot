import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.api.telegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommandWithArgs
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.flushAccumulatedUpdates
import dev.inmo.tgbotapi.types.chat.ExtendedBot
import dev.inmo.tgbotapi.types.message.MarkdownV2
import dev.inmo.tgbotapi.utils.RiskFeature
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
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import mu.KotlinLogging
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.net.SocketException
import java.time.Duration

val config = Json.decodeFromString<Config>(File("data/config.json").readText())

val commonHttpClient = HttpClient(OkHttp)
val proxiedHttpClient = HttpClient(OkHttp) {
    engine {
        proxy = ProxyBuilder.socks(config.proxyHost, config.proxyPort)
    }
}

val logger = KotlinLogging.logger {}
lateinit var botSelf: ExtendedBot

private fun initDatabase() {
    Database.connect(config.database, user = config.dbUsername, password = config.dbPassword)
    transaction {
        addLogger(Slf4jSqlDebugLogger)
    }
}

@OptIn(RiskFeature::class)
suspend fun main() {
    initDatabase()
    val telegramBotAPIUrlsKeeper = TelegramAPIUrlsKeeper(config.token, config.botApiUrl)
    val bot = telegramBot(telegramBotAPIUrlsKeeper)
    logger.info("Bot start.")

    embeddedServer(Netty, port = config.serverPort, host = config.serverHost) {
        install(WebSockets) {
            pingPeriod = Duration.ofMinutes(1)
        }
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

        onCommandWithArgs("sql") { msg, args ->
            if (msg.from?.id?.chatId != config.admin) {
                sendMessage(msg.chat, Constants.notOwner)
                return@onCommandWithArgs
            }
            val cmd = args.joinToString(" ")
            val result = StringBuilder()
            try {
                transaction {
                    exec(cmd) { rs ->
                        result.appendLine("```")
                        val cols = List(rs.metaData.columnCount) { rs.metaData.getColumnName(it + 1) }
                        val json = buildJsonArray {
                            while (rs.next()) {
                                addJsonObject {
                                    cols.forEachIndexed { index, name ->
                                        when (val obj = rs.getObject(index + 1)) {
                                            null -> put(name, JsonNull)
                                            is Boolean -> put(name, obj)
                                            is Number -> put(name, obj)
                                            is String -> put(name, obj)
                                            else -> put(name, obj.toString())
                                        }
                                    }
                                }
                            }
                        }
                        val formatter = Json { prettyPrint = true }
                        result.append(formatter.encodeToString(json))
                        result.appendLine().append("```")
                    } ?: result.append("Empty result")
                }
            } catch (e: ExposedSQLException) {
                result.append("SQL statement error")
            }

            sendMessage(msg.chat, result.toString(), parseMode = MarkdownV2)
        }

        installCS408()
        installTwiFucker()
        installJoinRequestVerification()
    }.join()
}
