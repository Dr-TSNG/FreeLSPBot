package function

import Chinese
import Constants
import English
import WebAppDataWrapper
import botSelf
import config
import database.CommonChatsTable
import database.JoinRequestDao
import database.LogDao
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.chat.get.getChatAdministrators
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.approveChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.declineChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onChatJoinRequest
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommandWithArgs
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.row
import dev.inmo.tgbotapi.extensions.utils.types.buttons.webAppButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.utils.RiskFeature
import dev.inmo.tgbotapi.utils.TelegramAPIUrlsKeeper
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import logger
import org.jetbrains.exposed.sql.transactions.transaction
import plugin.Captcha
import util.BotUtils.detailName
import util.BotUtils.fullName
import util.BotUtils.getGroupAdmin
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

private enum class LogMessage {
    CREATE, PASS, FAIL, TIMEOUT, MANUAL_PASS, MANUAL_DECLINE
}

private val userPending = ConcurrentHashMap<String, Verification>()

private fun log(chat: PublicChat, user: User, message: LogMessage, admin: User? = null) {
    transaction {
        LogDao.new {
            this.type = "JOIN_REQUEST"
            this.date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            this.chat = chat.id.chatId
            this.user = user.id.chatId
            this.admin = admin?.id?.chatId
            this.level = "info"
            this.message = message.name
        }
    }
    logger.info("Join request: chat ${chat.detailName} ${user.detailName} type $message")
}

private suspend fun TelegramBot.createVerification(dao: JoinRequestDao, chat: PublicChat, user: User, easyMode: Boolean) {
    log(chat, user, LogMessage.CREATE)
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
        text = String.format(Constants.groupVerifyMessage, user.fullName),
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
                log(chat, user, LogMessage.TIMEOUT)
                runCatching { declineChatJoinRequest(chat, user) }
                transaction { dao.total++ }
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

private suspend fun TelegramBot.getDaoOrSendError(msg: Message): JoinRequestDao? {
    if (msg.chat !is PublicChat) {
        sendTextMessage(msg.chat, Constants.groupOnly)
        return null
    }
    val dao = transaction {
        JoinRequestDao.findById(msg.chat.id.chatId)
    }
    if (dao == null) {
        sendTextMessage(msg.chat, Constants.groupNotInWhiteList)
        return null
    }
    return dao
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
                            log(chat, user, LogMessage.PASS)
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification successful"))
                            runCatching { bot.approveChatJoinRequest(chat, user) }
                            transaction {
                                dao.total++
                                dao.accepted++
                            }
                            bot.sendTextMessage(user, language.passVerifyPrivate)
                            bot.sendTextMessage(chat, String.format(Constants.passVerifyGroup, user.fullName))
                            doClean(bot)
                        }

                        "Failure" -> {
                            log(chat, user, LogMessage.FAIL)
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification failed"))
                            runCatching { bot.declineChatJoinRequest(chat, user) }
                            transaction { dao.total++ }
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
@OptIn(RiskFeature::class)
suspend fun installJoinRequestVerification() {
    suspend fun checkAdminCanChangeInfo(group: PublicChat, user: User?): Boolean {
        val admin = getGroupAdmin(group, user) { it.canChangeInfo }
        if (admin == null) {
            sendTextMessage(group, String.format(Constants.adminRequired, "CanChangeInfo"))
            return false
        }
        return true
    }

    onCommandWithArgs("jrctl") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.from)) return@onCommandWithArgs
        val ctl = run {
            if (args.size != 1 || (args[0] != "on" && args[0] != "off")) {
                sendTextMessage(group, Constants.invalidCommand)
                return@onCommandWithArgs
            }
            args[0] == "on"
        }
        transaction { dao.enabled = ctl }
        logger.info("Group ${group.detailName} changed join request verification status to $ctl")
        sendTextMessage(msg.chat, Constants.setSuccessful)
    }

    onCommand("jr_info") { msg ->
        val dao = getDaoOrSendError(msg) ?: return@onCommand
        sendTextMessage(msg.chat, String.format(Constants.joinRequestSettings, dao.enabled.toString(), dao.method, dao.timeout, dao.fail2ban))
    }

    onCommand("jr_statistics") { msg ->
        val dao = getDaoOrSendError(msg) ?: return@onCommand
        sendTextMessage(msg.chat, String.format(Constants.joinRequestStatistics, dao.total, dao.accepted, 100.0 * dao.accepted / dao.total))
    }

    onCommandWithArgs("jr_timeout") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.from)) return@onCommandWithArgs
        if (args.size != 1 || (Duration.parseOrNull(args[0])?.inWholeSeconds ?: 0) <= 60) {
            sendTextMessage(msg.chat, Constants.invalidCommand)
        } else {
            transaction { dao.timeout = args[0] }
            logger.info("Group ${group.detailName} changed join request verification timeout to ${args[0]}")
            sendTextMessage(group, Constants.setSuccessful)
        }
    }

    onCommandWithArgs("jr_fail2ban") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.from)) return@onCommandWithArgs
        if (args.size != 1 || (Duration.parseOrNull(args[0])?.inWholeSeconds ?: 0) <= 60) {
            sendTextMessage(msg.chat, Constants.invalidCommand)
        } else {
            transaction { dao.fail2ban = args[0] }
            logger.info("Group ${group.detailName} changed join request verification fail2ban to ${args[0]}")
            sendTextMessage(group, Constants.setSuccessful)
        }
    }

    onChatJoinRequest(
        initialFilter = { req ->
            getChatAdministrators(req.chat).any {
                it.user.id == botSelf.id && it.canInviteUsers && it.canRestrictMembers
            } && userPending.none { it.value.chat.id.chatId == req.chat.id.chatId && it.value.user.id.chatId == req.user.id.chatId }
        }
    ) { req ->
        val dao = transaction {
            JoinRequestDao.findById(req.chat.id.chatId)
        } ?: return@onChatJoinRequest
        if (!dao.enabled) return@onChatJoinRequest

        val commonChats = CommonChatsTable.getCommonChatsForUserId(req.user.id.chatId)
        val easyMode = commonChats >= (dao.commonChatEasy ?: Byte.MAX_VALUE)
        if (commonChats < (dao.commonChatLeast ?: 0)) {
            runCatching { declineChatJoinRequest(req) }
            val msg = sendTextMessage(req.chat, String.format(Constants.filteredSuspiciousUser, req.user.detailName))
            launch {
                delay(Duration.parse("1m"))
                deleteMessage(msg)
            }
            return@onChatJoinRequest
        }

        createVerification(dao, req.chat, req.user, easyMode)
    }

    onDataCallbackQuery(
        initialFilter = { ManualPassCallback.isValid(it.data) }
    ) { query ->
        val verification = ManualPassCallback.decode(query.data) ?: return@onDataCallbackQuery
        val admin = getGroupAdmin(verification.chat, query.from) { it.canInviteUsers } ?: return@onDataCallbackQuery
        with(verification) {
            mutex.withLock {
                if (!userPending.containsKey(token)) return@withLock
                log(chat, user, LogMessage.MANUAL_PASS, admin.user)
                runCatching { approveChatJoinRequest(chat, user) }
                sendTextMessage(user, language.manualPassPrivate)
                sendTextMessage(chat, String.format(Constants.manualPassGroup, query.from.fullName, user.fullName))
                doClean(this@onDataCallbackQuery)
            }
        }
    }

    onDataCallbackQuery(
        initialFilter = { ManualDeclineCallback.isValid(it.data) }
    ) { query ->
        val verification = ManualDeclineCallback.decode(query.data) ?: return@onDataCallbackQuery
        val admin = getGroupAdmin(verification.chat, query.from) { it.canRestrictMembers } ?: return@onDataCallbackQuery
        with(verification) {
            mutex.withLock {
                if (!userPending.containsKey(token)) return@withLock
                log(chat, user, LogMessage.MANUAL_DECLINE, admin.user)
                runCatching { declineChatJoinRequest(chat, user) }
                bot.kickUser(chat, user, dao.fail2ban)
                sendTextMessage(chat, String.format(Constants.manualDeclineGroup, query.from.fullName, user.fullName))
                doClean(this@onDataCallbackQuery)
            }
        }
    }
}
