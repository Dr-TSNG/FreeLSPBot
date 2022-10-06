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
import dev.inmo.tgbotapi.extensions.api.chat.members.banChatMember
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
import dev.inmo.tgbotapi.types.message.MarkdownV2
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

private enum class JRLogMessage {
    IGNORE, CREATE, PASS, FAIL, TIMEOUT, MANUAL_PASS, MANUAL_DECLINE
}

private val userPending = ConcurrentHashMap<String, Verification>()

private fun log(chat: PublicChat, user: User, message: JRLogMessage, admin: User? = null) {
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

private infix fun String.masked(dao: JoinRequestDao) = when {
    !dao.nameMask -> this
    length == 1 -> this
    length < 5 -> replaceRange(1, length - 1, "▓")
    else -> first() + "▓▓▓▓" + last()
}

private suspend fun TelegramBot.createVerification(dao: JoinRequestDao, chat: PublicChat, user: User, easyMode: Boolean) {
    log(chat, user, JRLogMessage.CREATE)
    var token: String
    do {
        token = StringUtils.getRandomString(16)
    } while (userPending.containsKey(token))
    val language = if (user.isChinese) Chinese else English
    val privateVerifyMessage = sendTextMessage(
        chat = user,
        text = language.privateVerifyMessage.format(user.fullNameMention, dao.timeout),
        parseMode = MarkdownV2,
        protectContent = true,
        replyMarkup = inlineKeyboard {
            row { webAppButton(language.startVerify, config.webApiUrl + "/captcha/?token=$token") }
        }
    )
    val groupVerifyMessage = sendTextMessage(
        chat = chat,
        text = Constants.groupVerifyMessage.format(user.fullNameMention.masked(dao)),
        parseMode = MarkdownV2,
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
                log(chat, user, JRLogMessage.TIMEOUT)
                runCatching { declineChatJoinRequest(chat, user) }
                transaction { dao.total++ }
                kickUser(chat, user, dao.fail2ban)
                sendTextMessage(user, language.failVerifyPrivate.format(dao.fail2ban))
                sendAutoDeleteMessage(chat, Constants.failVerifyGroup.format(user.fullNameMention.masked(dao)), parseMode = MarkdownV2)
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
        sendAutoDeleteMessage(msg.chat, Constants.groupNotInWhiteList)
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
                            log(chat, user, JRLogMessage.PASS)
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification successful"))
                            runCatching { bot.approveChatJoinRequest(chat, user) }
                            transaction {
                                dao.total++
                                dao.accepted++
                            }
                            bot.sendTextMessage(user, language.passVerifyPrivate)
                            bot.sendTextMessage(chat, Constants.passVerifyGroup.format(user.fullNameMention), parseMode = MarkdownV2)
                            doClean(bot)
                        }

                        "Failure" -> {
                            log(chat, user, JRLogMessage.FAIL)
                            close(CloseReason(CloseReason.Codes.NORMAL, "Verification failed"))
                            runCatching { bot.declineChatJoinRequest(chat, user) }
                            transaction { dao.total++ }
                            bot.kickUser(chat, user, dao.fail2ban)
                            bot.sendTextMessage(user, language.failVerifyPrivate.format(dao.fail2ban))
                            bot.sendAutoDeleteMessage(chat, Constants.failVerifyGroup.format(user.fullNameMention.masked(dao)), parseMode = MarkdownV2)
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
            sendTextMessage(group, Constants.adminRequired.format("CanChangeInfo"))
            return false
        }
        return true
    }

    suspend fun checkOnOff(cmd: String, msg: Message, args: Array<String>, transaction: (JoinRequestDao, Boolean) -> Unit) {
        val dao = getDaoOrSendError(msg) ?: return
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.from)) return
        if (args.size != 1 || (args[0] != "on" && args[0] != "off")) {
            sendTextMessage(group, Constants.invalidCommand)
            return
        }
        val ctl = args[0] == "on"
        transaction { transaction(dao, ctl) }
        logger.info("Group ${group.detailName} changed join request verification $cmd to $ctl")
        sendTextMessage(msg.chat, Constants.setSuccessful)
    }

    onCommandWithArgs("jrctl") { msg, args ->
        checkOnOff("enabled", msg, args) { dao, ctl ->
            dao.enabled = ctl
        }
    }

    onCommand("jr_info") { msg ->
        val dao = getDaoOrSendError(msg) ?: return@onCommand
        sendTextMessage(msg.chat, Constants.joinRequestSettings.format(dao.enabled.toString(), dao.method, dao.timeout, dao.fail2ban, dao.regexBan ?: "未启用"))
    }

    onCommand("jr_statistics") { msg ->
        val dao = getDaoOrSendError(msg) ?: return@onCommand
        sendTextMessage(msg.chat, Constants.joinRequestStatistics.format(dao.total, dao.accepted, 100.0 * dao.accepted / dao.total))
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

    onCommandWithArgs("jr_namemask") { msg, args ->
        checkOnOff("name_mask", msg, args) { dao, ctl ->
            dao.nameMask = ctl
        }
    }

    onCommandWithArgs("jr_regexban") { msg, args ->
        val dao = getDaoOrSendError(msg) ?: return@onCommandWithArgs
        val group = msg.chat as GroupChat
        if (!checkAdminCanChangeInfo(group, msg.from)) return@onCommandWithArgs
        if (args.size != 1) {
            sendTextMessage(msg.chat, Constants.invalidCommand)
        } else {
            if (args[0] == "off") {
                transaction { dao.regexBan = null }
            } else {
                try {
                    args[0].toRegex()
                } catch (e: PatternSyntaxException) {
                    sendTextMessage(msg.chat, Constants.invalidRegex)
                    return@onCommandWithArgs
                }
                transaction { dao.regexBan = args[0] }
            }
            logger.info("Group ${group.detailName} changed join request verification regex_ban to ${dao.regexBan}")
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

        if (dao.regexBan != null) {
            if (req.user.fullNameMention.matches(dao.regexBan!!.toRegex())) {
                declineChatJoinRequest(req)
                banChatMember(req.chat, req.user)
                sendAutoDeleteMessage(req.chat, Constants.regexBanned.format(req.user.fullNameMention.masked(dao)))
                return@onChatJoinRequest
            }
        }

        val commonChats = CommonChatsTable.getCommonChatsForUserId(req.user.id.chatId)
        val easyMode = commonChats >= (dao.commonChatEasy ?: Byte.MAX_VALUE)
        if (commonChats < (dao.commonChatLeast ?: 0)) {
            runCatching { declineChatJoinRequest(req) }
            log(req.chat, req.user, JRLogMessage.IGNORE)
            sendAutoDeleteMessage(req.chat, Constants.filteredSuspiciousUser.format(req.user.fullNameMention.masked(dao)), parseMode = MarkdownV2)
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
                log(chat, user, JRLogMessage.MANUAL_PASS, admin.user)
                runCatching { approveChatJoinRequest(chat, user) }
                sendTextMessage(user, language.manualPassPrivate)
                sendTextMessage(chat, Constants.manualPassGroup.format(query.from.fullNameMention, user.fullNameMention), parseMode = MarkdownV2)
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
                log(chat, user, JRLogMessage.MANUAL_DECLINE, admin.user)
                runCatching { declineChatJoinRequest(chat, user) }
                bot.banChatMember(chat, user)
                sendAutoDeleteMessage(chat, Constants.manualDeclineGroup.format(query.from.fullNameMention, user.fullNameMention.masked(dao)), parseMode = MarkdownV2)
                doClean(this@onDataCallbackQuery)
            }
        }
    }
}
