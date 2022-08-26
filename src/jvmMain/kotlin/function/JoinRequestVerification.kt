package function

import Chinese
import Constants
import English
import WebAppDataWrapper
import botSelf
import config
import database.JoinRequestDao
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.chat.get.getChatAdministrators
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.approveChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.declineChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onChatJoinRequest
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.extensions.utils.types.buttons.webAppButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.utils.TelegramAPIUrlsKeeper
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import logger
import org.jetbrains.exposed.sql.transactions.transaction
import plugin.Captcha
import util.BotUtils.detailName
import util.BotUtils.fullName
import util.BotUtils.fullNameExt
import util.BotUtils.getCommonChats
import util.BotUtils.isChinese
import util.BotUtils.kickUser
import util.StringUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.time.Duration

private class Verification(
    val dao: JoinRequestDao,
    val token: String,
    val user: User,
    val chat: PublicChat,
    val language: Constants,
    val easyMode: Boolean,
    val privateVerifyMessage: Message,
    val groupVerifyMessage: Message
) {
    val mutex = Mutex()
    val scope = CoroutineScope(Dispatchers.Default)

    suspend fun doClean(bot: TelegramBot) {
        bot.deleteMessage(privateVerifyMessage)
        bot.deleteMessage(groupVerifyMessage)
        userPending.remove(token)
        scope.cancel()
    }
}

private object ManualPassCallback {
    fun encode(token: String): String = "manualPass:$token"
    fun isValid(data: String): Boolean = data.startsWith("manualPass:")
    fun decode(data: String): Verification? = userPending[data.substringAfter(':')]
}

private object ManualDeclineCallback {
    fun encode(token: String): String = "manualDecline:$token"
    fun isValid(data: String): Boolean = data.startsWith("manualDecline:")
    fun decode(data: String): Verification? = userPending[data.substringAfter(':')]
}

private val userPending = ConcurrentHashMap<String, Verification>()

private suspend fun TelegramBot.createVerification(dao: JoinRequestDao, chat: PublicChat, user: User, easyMode: Boolean) {
    logger.info("Create new verification for ${user.detailName} in chat ${chat.detailName}")
    var token: String
    do {
        token = StringUtils.getRandomString(16)
    } while (userPending.containsKey(token))
    val language = if (user.isChinese) Chinese else English
    val privateVerifyMessage = sendTextMessage(
        chat = user,
        text = String.format(language.privateVerifyMessage, user.fullName, dao.timeout),
        protectContent = true,
        replyMarkup = inlineKeyboard {
            row { webAppButton(language.startVerify, config.webApiUrl + "/captcha/?token=$token") }
        }
    )
    val groupVerifyMessage = sendTextMessage(
        chat = chat,
        text = String.format(Constants.groupVerifyMessage, user.fullNameExt),
        replyMarkup = inlineKeyboard {
            row {
                +CallbackDataInlineKeyboardButton(Constants.manualPass, ManualPassCallback.encode(token))
            }
            row {
                +CallbackDataInlineKeyboardButton(Constants.manualDecline, ManualDeclineCallback.encode(token))
            }
        }
    )

    userPending[token] = Verification(
        dao, token, user, chat, language,
        easyMode, privateVerifyMessage, groupVerifyMessage
    ).also {
        it.scope.launch {
            delay(Duration.parse(dao.timeout))
            it.mutex.withLock {
                if (!userPending.containsKey(token)) return@withLock
                logger.info("Verification timeout for ${user.detailName} in chat ${chat.detailName}")
                runCatching { declineChatJoinRequest(chat, user) }
                kickUser(chat, user, dao.fail2ban)
                sendTextMessage(user, String.format(language.failVerifyPrivate, dao.fail2ban))
                val failMessage = sendTextMessage(chat, String.format(Constants.failVerifyGroup, user.fullName))
                CoroutineScope(Dispatchers.Default).launch {
                    delay(Duration.parse("1m"))
                    deleteMessage(failMessage)
                }
                it.doClean(this@createVerification)
            }
        }
    }
}

fun Routing.configureJoinRequestRouting(
    telegramBotAPIUrlsKeeper: TelegramAPIUrlsKeeper,
    bot: TelegramBot
) = route("captcha/") {
    static {
        files(File("data/captcha"))
        default("data/captcha/index.html")
    }
    webSocket("ws") {
        logger.info("New WebSocket connection")
        val frame = incoming.receive()
        if (frame !is Frame.Text) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Only text frames are accepted"))
            return@webSocket
        }
        val webAppCheckData = Json.decodeFromString<WebAppDataWrapper>(frame.readText())
        val isSafe = telegramBotAPIUrlsKeeper.checkWebAppData(webAppCheckData.data, webAppCheckData.hash)
        val verification = userPending[webAppCheckData.token]
        if (!isSafe || verification == null) {
            logger.debug("Invalid session: isSafe=$isSafe")
            close(CloseReason(CloseReason.Codes.NORMAL, "Verification session invalid"))
            return@webSocket
        }

        val sessionId: String
        with(verification) {
            mutex.withLock {
                if (!userPending.containsKey(token)) {
                    close(CloseReason(CloseReason.Codes.NORMAL, "Verification session expired"))
                    return@webSocket
                }
                sessionId = Captcha.createSession(verification.easyMode)
                outgoing.send(Frame.Text(config.captchaApiUrl + "/?sessionId=" + sessionId))
            }
        }

        while (true) {
            delay(1000)
            with(verification) {
                mutex.withLock {
                    if (!userPending.containsKey(token)) {
                        close(CloseReason(CloseReason.Codes.NORMAL, "Verification session expired"))
                        return@webSocket
                    }
                    when (Captcha.getVerifyResult(sessionId)) {
                        null -> {
                            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Captcha session expired"))
                            return@webSocket
                        }

                        "Success" -> {
                            logger.info("${user.detailName} passed verification to chat ${chat.detailName}")
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification successful"))
                            runCatching { bot.approveChatJoinRequest(chat, user) }
                            bot.sendTextMessage(user, language.passVerifyPrivate)
                            bot.sendTextMessage(chat, String.format(Constants.passVerifyGroup, user.fullName))
                            doClean(bot)
                        }

                        "Failure" -> {
                            logger.info("${user.detailName} failed verification to chat ${chat.detailName}")
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification failed"))
                            runCatching { bot.declineChatJoinRequest(chat, user) }
                            bot.kickUser(chat, user, dao.fail2ban)
                            bot.sendTextMessage(user, String.format(language.failVerifyPrivate, dao.fail2ban))
                            val failMessage = bot.sendTextMessage(chat, String.format(Constants.failVerifyGroup, user.fullName))
                            CoroutineScope(Dispatchers.Default).launch {
                                delay(Duration.parse("1m"))
                                bot.deleteMessage(failMessage)
                            }
                            doClean(bot)
                        }
                    }
                }
            }
        }
    }
}

context(BehaviourContext)
suspend fun installJoinRequestVerification() {
    onChatJoinRequest(
        initialFilter = { req ->
            getChatAdministrators(req.chat).any {
                it.user.id == botSelf.id && it.canInviteUsers && it.canRestrictMembers
            } && userPending.none { it.value.user.id == req.user.id }
        }
    ) { req ->
        val dao = transaction {
            JoinRequestDao.findById(req.chat.id.chatId)
        } ?: return@onChatJoinRequest

        var commonChats: Int? = null
        var easyMode = false
        if (dao.commonChatLeast != null) {
            commonChats = getCommonChats(req.chat.id.chatId, req.user.id.chatId)
            if (commonChats == null) {
                runCatching { declineChatJoinRequest(req) }
                val language = if (req.user.isChinese) Chinese else English
                sendTextMessage(req.user, language.errorVerifyPrivate)
                return@onChatJoinRequest
            } else if (commonChats < dao.commonChatLeast!!) {
                runCatching { declineChatJoinRequest(req) }
                val msg = sendTextMessage(req.chat, String.format(Constants.filteredSuspiciousUser, req.user.detailName))
                launch {
                    delay(Duration.parse("1m"))
                    deleteMessage(msg)
                }
                return@onChatJoinRequest
            }
        }
        if (dao.commonChatEasy != null) {
            if (commonChats == null) commonChats = getCommonChats(req.chat.id.chatId, req.user.id.chatId)
            if (commonChats != null && commonChats >= dao.commonChatEasy!!) easyMode = true
        }

        createVerification(dao, req.chat, req.user, easyMode)
    }

    onDataCallbackQuery(
        initialFilter = { ManualPassCallback.isValid(it.data) }
    ) { query ->
        val verification = ManualPassCallback.decode(query.data) ?: return@onDataCallbackQuery
        val admins = getChatAdministrators(verification.chat)
        val admin = admins.find { it.user.id == query.from.id && it.canInviteUsers } ?: return@onDataCallbackQuery
        verification.mutex.withLock {
            if (!userPending.containsKey(verification.token)) return@withLock
            logger.info("Admin ${admin.user.detailName} manually passed ${verification.user.detailName} in chat ${verification.chat.detailName}")
            runCatching { approveChatJoinRequest(verification.chat, verification.user) }
            sendTextMessage(verification.user, verification.language.manualPassPrivate)
            sendTextMessage(verification.chat, String.format(Constants.manualPassGroup, query.from.fullName, verification.user.fullName))
            verification.doClean(this)
        }
    }

    onDataCallbackQuery(
        initialFilter = { ManualDeclineCallback.isValid(it.data) }
    ) { query ->
        val verification = ManualDeclineCallback.decode(query.data) ?: return@onDataCallbackQuery
        val admins = getChatAdministrators(verification.chat)
        val admin = admins.find { it.user.id == query.from.id && it.canRestrictMembers } ?: return@onDataCallbackQuery
        verification.mutex.withLock {
            if (!userPending.containsKey(verification.token)) return@withLock
            logger.info("Admin ${admin.user.detailName} manually declined ${verification.user.detailName} in chat ${verification.chat.detailName}")
            runCatching { declineChatJoinRequest(verification.chat, verification.user) }
            bot.kickUser(verification.chat, verification.user, verification.dao.fail2ban)
            sendTextMessage(verification.chat, String.format(Constants.manualDeclineGroup, query.from.fullName, verification.user.fullName))
            verification.doClean(this)
        }
    }
}
