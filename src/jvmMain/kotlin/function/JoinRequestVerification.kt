package function

import Messages
import WebAppDataWrapper
import botSelf
import config
import database.CommonChatsTable
import database.JoinRequestDao
import database.LogDao
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.approveChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.invite_links.declineChatJoinRequest
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onChatJoinRequest
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommandWithArgs
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.extensions.utils.types.buttons.flatInlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.webAppButton
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.message.MarkdownV2
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.utils.RiskFeature
import dev.inmo.tgbotapi.utils.TelegramAPIUrlsKeeper
import dev.inmo.tgbotapi.utils.extensions.threadIdOrNull
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
import util.BotUtils.fullNameMention
import util.BotUtils.getGroupAdmin
import util.BotUtils.isChinese
import util.BotUtils.kickUser
import util.BotUtils.sendAutoDeleteMessage
import util.StringUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException
import kotlin.collections.set
import kotlin.time.Duration

private class Verification(
    val dao: JoinRequestDao,
    val token: String,
    val user: User,
    val group: GroupChat,
    val language: Messages.Private,
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

private enum class JRLogMessage {
    IGNORE, CREATE, PASS, FAIL, TIMEOUT, MANUAL_PASS, MANUAL_DECLINE
}

private val userPending = ConcurrentHashMap<String, Verification>()

private fun log(group: GroupChat, user: User, message: JRLogMessage, admin: User? = null) {
    transaction {
        LogDao.new {
            this.type = "JOIN_REQUEST"
            this.date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            this.chat = group.id.chatId
            this.user = user.id.chatId
            this.admin = admin?.id?.chatId
            this.level = "info"
            this.message = message.name
        }
    }
    logger.info("Join request: chat ${group.detailName} ${user.detailName} type $message")
}

private infix fun String.masked(dao: JoinRequestDao) = when {
    !dao.nameMask -> this
    length == 1 -> this
    length < 5 -> replaceRange(1, length - 1, "▓")
    else -> first() + "▓▓▓▓" + last()
}

private suspend fun TelegramBot.createVerification(dao: JoinRequestDao, group: GroupChat, user: User, easyMode: Boolean) {
    log(group, user, JRLogMessage.CREATE)
    var token: String
    do {
        token = StringUtils.getRandomString(16)
    } while (userPending.containsKey(token))
    val language = if (user.isChinese) Messages.Chinese else Messages.English
    val privateVerifyMessage = sendTextMessage(
        chat = user,
        text = language.verifyDesc(user.fullNameMention, dao.timeout),
        parseMode = MarkdownV2,
        protectContent = true,
        replyMarkup = flatInlineKeyboard {
            webAppButton(language.verifyStart, config.webApiUrl + "/captcha/?token=$token")
        }
    )
    val groupVerifyMessage = sendTextMessage(
        chat = group,
        threadId = dao.logThread,
        text = Messages.verifyNew(user.fullNameMention.masked(dao)),
        parseMode = MarkdownV2,
        replyMarkup = inlineKeyboard {
            +CallbackDataInlineKeyboardButton(Messages.verifyManualAccept, ManualPassCallback.encode(token))
            +CallbackDataInlineKeyboardButton(Messages.verifyManualDecline, ManualDeclineCallback.encode(token))
        }
    )

    userPending[token] = Verification(
        dao, token, user, group, language,
        easyMode, privateVerifyMessage, groupVerifyMessage
    ).also {
        it.scope.launch {
            delay(Duration.parse(dao.timeout))
            it.mutex.withLock {
                if (!userPending.containsKey(token)) return@withLock
                log(group, user, JRLogMessage.TIMEOUT)
                runCatching { declineChatJoinRequest(group, user) }
                transaction { dao.total++ }
                kickUser(group, user, dao.fail2ban)
                sendTextMessage(user, language.verifyFailed(dao.fail2ban))
                sendAutoDeleteMessage(
                    chat = group,
                    threadId = dao.logThread,
                    text = Messages.verifyFailed(user.fullNameMention.masked(dao)),
                    parseMode = MarkdownV2
                )
                it.doClean(this@createVerification)
            }
        }
    }
}

private suspend fun TelegramBot.getDaoOrSendError(msg: Message): JoinRequestDao? {
    if (msg.chat !is GroupChat) {
        sendTextMessage(msg.chat, Messages.cmdGroupOnly, threadId = msg.threadIdOrNull)
        return null
    }
    val dao = transaction {
        JoinRequestDao.findById(msg.chat.id.chatId)
    }
    if (dao == null) {
        sendAutoDeleteMessage(msg.chat, Messages.cmdGroupNotInWhiteList, threadId = msg.threadIdOrNull)
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
                            log(group, user, JRLogMessage.PASS)
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification successful"))
                            runCatching { bot.approveChatJoinRequest(group, user) }
                            transaction {
                                dao.total++
                                dao.accepted++
                            }
                            bot.sendTextMessage(user, language.verifyPassed)
                            bot.sendTextMessage(
                                chat = group,
                                threadId = dao.logThread,
                                text = Messages.verifyPassed(user.fullNameMention),
                                parseMode = MarkdownV2
                            )
                            doClean(bot)
                        }

                        "Failure" -> {
                            log(group, user, JRLogMessage.FAIL)
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification failed"))
                            runCatching { bot.declineChatJoinRequest(group, user) }
                            transaction { dao.total++ }
                            bot.kickUser(group, user, dao.fail2ban)
                            bot.sendTextMessage(user, language.verifyFailed(dao.fail2ban))
                            bot.sendAutoDeleteMessage(
                                chat = group,
                                threadId = dao.logThread,
                                text = Messages.verifyFailed(user.fullNameMention.masked(dao)),
                                parseMode = MarkdownV2
                            )
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
    suspend fun checkAdminCanChangeInfo(group: GroupChat, threadId: MessageThreadId?, user: User?): Boolean {
        val admin = getGroupAdmin(group, user) { it.canChangeInfo }
        if (admin == null) {
            sendTextMessage(group, Messages.cmdAdminRequired("CanChangeInfo"), threadId = threadId)
            return false
        }
        return true
    }

    onCommandWithArgs("jrctl") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.threadIdOrNull, msg.from)) return@onCommandWithArgs
        if (args.size == 1 && (args[0] == "on" || args[0] == "off")) {
            transaction { dao.enabled = args[0] == "on" }
            logger.info("Group ${group.detailName} set jrctl to ${args[0]}")
            sendTextMessage(group, if (dao.enabled) Messages.verifyEnabled else Messages.verifyDisabled, threadId = msg.threadIdOrNull)
        } else {
            sendTextMessage(group, Messages.cmdIllegalArgument, threadId = msg.threadIdOrNull)
        }
    }

    onCommand("jr_info") { msg ->
        val dao = getDaoOrSendError(msg) ?: return@onCommand
        sendTextMessage(
            chat = msg.chat,
            threadId = msg.threadIdOrNull,
            text = Messages.verifySettings(dao.enabled, dao.timeout, dao.fail2ban, dao.regexBan ?: "未启用")
        )
    }

    onCommand("jr_statistics") { msg ->
        val dao = getDaoOrSendError(msg) ?: return@onCommand
        sendTextMessage(
            chat = msg.chat,
            threadId = msg.threadIdOrNull,
            text = Messages.verifyStatistics(dao.total, dao.accepted)
        )
    }

    onCommandWithArgs("jr_timeout") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.threadIdOrNull, msg.from)) return@onCommandWithArgs
        val time = Duration.parseOrNull(args[0])?.inWholeSeconds ?: 0
        if (args.size == 1 && time in 60..3600) {
            transaction { dao.timeout = args[0] }
            logger.info("Group ${group.detailName} set jr_timeout to ${args[0]}")
            sendTextMessage(group, Messages.verifySetTimeout(args[0]), threadId = msg.threadIdOrNull)
        } else {
            sendTextMessage(msg.chat, Messages.verifyTimeoutLimit, threadId = msg.threadIdOrNull)
        }
    }

    onCommandWithArgs("jr_fail2ban") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.threadIdOrNull, msg.from)) return@onCommandWithArgs
        val time = Duration.parseOrNull(args[0])?.inWholeSeconds ?: 0
        if (args.size == 1 && time >= 60) {
            transaction { dao.fail2ban = args[0] }
            logger.info("Group ${group.detailName} set jr_fail2ban to ${args[0]}")
            sendTextMessage(group, Messages.verifySetFail2Ban(args[0]), threadId = msg.threadIdOrNull)
        } else {
            sendTextMessage(msg.chat, Messages.verifyFail2BanLimit, threadId = msg.threadIdOrNull)
        }
    }

    onCommandWithArgs("jr_namemask") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.threadIdOrNull, msg.from)) return@onCommandWithArgs
        if (args.size == 1 && (args[0] == "on" || args[0] == "off")) {
            transaction { dao.nameMask = args[0] == "on" }
            logger.info("Group ${group.detailName} set jr_namemask to ${args[0]}")
            sendTextMessage(group, if (dao.enabled) Messages.verifyNameMaskEnabled else Messages.verifyNameMaskDisabled, threadId = msg.threadIdOrNull)
        } else {
            sendTextMessage(group, Messages.cmdIllegalArgument, threadId = msg.threadIdOrNull)
        }
    }

    onCommandWithArgs("jr_regexban") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.threadIdOrNull, msg.from)) return@onCommandWithArgs
        if (args.size != 1) {
            sendTextMessage(msg.chat, Messages.cmdIllegalArgument, threadId = msg.threadIdOrNull)
        } else {
            if (args[0] == "off") {
                transaction { dao.regexBan = null }
            } else {
                try {
                    args[0].toRegex()
                } catch (e: PatternSyntaxException) {
                    sendTextMessage(msg.chat, Messages.verifyRegexInvalid, threadId = msg.threadIdOrNull)
                    return@onCommandWithArgs
                }
                transaction { dao.regexBan = args[0] }
            }
            logger.info("Group ${group.detailName} set jr_regexban to ${dao.regexBan}")
            sendTextMessage(group, Messages.verifySetRegexBan(args[0]), threadId = msg.threadIdOrNull)
        }
    }

    onCommandWithArgs("jr_logthread") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.threadIdOrNull, msg.from)) return@onCommandWithArgs
        val threadId = args[0].toLongOrNull()
        if (args.size == 1 && threadId != null) {
            transaction { dao.logThread = if (threadId == 0L) null else threadId }
            logger.info("Group ${group.detailName} set jr_logthread to ${args[0]}")
            sendTextMessage(group, Messages.verifySetLogThread(threadId), threadId = msg.threadIdOrNull)
        } else {
            sendTextMessage(msg.chat, Messages.cmdIllegalArgument, threadId = msg.threadIdOrNull)
        }
    }

    onChatJoinRequest(
        initialFilter = { req ->
            val group = req.chat as? GroupChat ?: return@onChatJoinRequest false
            val hasAdmin = getGroupAdmin(group, botSelf) { it.canInviteUsers && it.canRestrictMembers } != null
            hasAdmin && userPending.none { it.value.group.id.chatId == group.id.chatId && it.value.user.id.chatId == req.user.id.chatId }
        }
    ) { req ->
        val group = req.chat as GroupChat
        val dao = transaction {
            JoinRequestDao.findById(group.id.chatId)
        } ?: return@onChatJoinRequest
        if (!dao.enabled) return@onChatJoinRequest

        if (dao.regexBan != null) {
            if (req.user.fullNameMention.matches(dao.regexBan!!.toRegex())) {
                declineChatJoinRequest(req)
                banChatMember(group, req.user)
                sendAutoDeleteMessage(group, Messages.verifyRegexBanned(req.user.fullNameMention.masked(dao)), threadId = dao.logThread)
                return@onChatJoinRequest
            }
        }

        val commonChats = CommonChatsTable.getCommonChatsForUserId(req.user.id.chatId)
        val easyMode = commonChats >= (dao.commonChatEasy ?: Byte.MAX_VALUE)
        if (commonChats < (dao.commonChatLeast ?: 0)) {
            runCatching { declineChatJoinRequest(req) }
            log(group, req.user, JRLogMessage.IGNORE)
            sendAutoDeleteMessage(group, Messages.verifyIgnored(req.user.fullNameMention.masked(dao)), parseMode = MarkdownV2, threadId = dao.logThread)
            return@onChatJoinRequest
        }

        createVerification(dao, group, req.user, easyMode)
    }

    onDataCallbackQuery(
        initialFilter = { ManualPassCallback.isValid(it.data) }
    ) { query ->
        val verification = ManualPassCallback.decode(query.data) ?: return@onDataCallbackQuery
        val admin = getGroupAdmin(verification.group, query.from) { it.canInviteUsers } ?: return@onDataCallbackQuery
        with(verification) {
            mutex.withLock {
                if (!userPending.containsKey(token)) return@withLock
                log(group, user, JRLogMessage.MANUAL_PASS, admin.user)
                runCatching { approveChatJoinRequest(group, user) }
                sendTextMessage(user, language.verifyManualAccepted, threadId = dao.logThread)
                sendTextMessage(
                    chat = group,
                    threadId = dao.logThread,
                    text = Messages.verifyManualAccepted(query.from.fullNameMention, user.fullNameMention),
                    parseMode = MarkdownV2
                )
                doClean(this@onDataCallbackQuery)
            }
        }
    }

    onDataCallbackQuery(
        initialFilter = { ManualDeclineCallback.isValid(it.data) }
    ) { query ->
        val verification = ManualDeclineCallback.decode(query.data) ?: return@onDataCallbackQuery
        val admin = getGroupAdmin(verification.group, query.from) { it.canRestrictMembers } ?: return@onDataCallbackQuery
        with(verification) {
            mutex.withLock {
                if (!userPending.containsKey(token)) return@withLock
                log(group, user, JRLogMessage.MANUAL_DECLINE, admin.user)
                runCatching { declineChatJoinRequest(group, user) }
                bot.banChatMember(group, user)
                sendAutoDeleteMessage(
                    chat = group,
                    threadId = dao.logThread,
                    text = Messages.verifyManualDeclined(query.from.fullNameMention, user.fullNameMention.masked(dao)),
                    parseMode = MarkdownV2
                )
                doClean(this@onDataCallbackQuery)
            }
        }
    }
}
