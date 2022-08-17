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
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
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
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
    val privateVerifyMessage: Message,
    val groupVerifyMessage: Message
) {
    val mutex = Mutex()
    val scope = CoroutineScope(Dispatchers.Default)
    var sessionId: String? = null
    var chances = dao.retryChances

    suspend fun doClean(bot: TelegramBot) {
        bot.deleteMessage(privateVerifyMessage)
        bot.deleteMessage(groupVerifyMessage)
        userPending.remove(token)
        scope.cancel()
    }
}

private object ChangeQuestionCallback {
    fun encode(token: String): String = "changeQuestion:$token"
    fun isValid(data: String): Boolean = data.startsWith("changeQuestion:")
    fun decode(data: String): Verification? = userPending[data.substringAfter(':')]
}

private object ManualPassCallback {
    fun encode(token: String): String = "manualPass:$token"
    fun isValid(data: String): Boolean = data.startsWith("manualPass:")
    fun decode(data: String): Verification? = userPending[data.substringAfter(':')]
}

private val userPending = ConcurrentHashMap<String, Verification>()

private suspend fun TelegramBot.createVerification(dao: JoinRequestDao, chat: PublicChat, user: User) {
    logger.info("Create new verification for ${user.detailName} in chat ${chat.detailName}")
    var token: String
    do {
        token = StringUtils.getRandomString(16)
    } while (userPending.containsKey(token))
    val language = if (user.isChinese) Chinese else English
    val privateVerifyMessage = sendTextMessage(
        chat = user,
        text = String.format(language.privateVerifyMessage, user.fullName, dao.timeout, dao.retryChances),
        protectContent = true,
        replyMarkup = inlineKeyboard {
            row { webAppButton(language.startVerify, config.webApiUrl + "/captcha/?token=$token") }
            row { +CallbackDataInlineKeyboardButton(language.changeQuestion, ChangeQuestionCallback.encode(token)) }
        }
    )
    val groupVerifyMessage = sendTextMessage(
        chat = chat,
        text = String.format(Constants.groupVerifyMessage, user.fullNameExt),
        replyMarkup = inlineKeyboard {
            row {
                +CallbackDataInlineKeyboardButton(Constants.manualPass, ManualPassCallback.encode(token))
            }
        }
    )

    userPending[token] = Verification(
        dao, token, user, chat, language,
        privateVerifyMessage, groupVerifyMessage
    ).also {
        it.scope.launch {
            delay(Duration.parse(dao.timeout))
            it.mutex.withLock {
                if (!userPending.containsKey(token)) return@withLock
                logger.info("Verification timeout for ${user.detailName} in chat ${chat.detailName}")
                declineChatJoinRequest(chat, user)
                kickUser(chat, user, dao.fail2ban)
                sendTextMessage(user, String.format(language.failVerifyPrivate, dao.fail2ban))
                sendTextMessage(chat, String.format(Constants.failVerifyGroup, user.fullName))
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
    post("load") {
        val requestBody = call.receiveText()
        val webAppCheckData = Json.decodeFromString<WebAppDataWrapper>(requestBody)
        val isSafe = telegramBotAPIUrlsKeeper.checkWebAppData(webAppCheckData.data, webAppCheckData.hash)
        val verification = userPending[webAppCheckData.token]

        if (isSafe && verification != null) {
            if (verification.sessionId == null) {
                verification.sessionId = Captcha.createSession()
            }
            call.respond(HttpStatusCode.OK, verification.sessionId!!)
        } else {
            call.respond(HttpStatusCode.Forbidden)
        }
    }
    post("complete") {
        val requestBody = call.receiveText()
        val webAppCheckData = Json.decodeFromString<WebAppDataWrapper>(requestBody)
        val isSafe = telegramBotAPIUrlsKeeper.checkWebAppData(webAppCheckData.data, webAppCheckData.hash)
        val verification = userPending[webAppCheckData.token]

        if (isSafe && verification != null) {
            with(verification) {
                val result = sessionId?.let { Captcha.getVerifyResult(it) }
                logger.info("Result for verification ${webAppCheckData.token}: $result")
                mutex.withLock {
                    if (!userPending.containsKey(token)) return@withLock
                    when (result) {
                        "Success" -> {
                            logger.info("${user.detailName} passed verification to chat ${chat.detailName}")
                            bot.approveChatJoinRequest(chat, user)
                            bot.sendTextMessage(user, language.passVerifyPrivate)
                            bot.sendTextMessage(chat, String.format(Constants.passVerifyGroup, user.fullName))
                            doClean(bot)
                        }

                        else -> {
                            logger.info("${user.detailName} failed verification to chat ${chat.detailName}")
                            bot.declineChatJoinRequest(chat, user)
                            bot.kickUser(chat, user, dao.fail2ban)
                            bot.sendTextMessage(user, String.format(language.failVerifyPrivate, dao.fail2ban))
                            bot.sendTextMessage(chat, String.format(Constants.failVerifyGroup, user.fullName))
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

        if (dao.commonChatFilter != null) {
            val commonChats = getCommonChats(req.chat.id.chatId, req.user.id.chatId)
            if (commonChats == null) {
                declineChatJoinRequest(req)
                val language = if (req.user.isChinese) Chinese else English
                sendTextMessage(req.user, language.errorVerifyPrivate)
                return@onChatJoinRequest
            } else if (commonChats < dao.commonChatFilter!!) {
                declineChatJoinRequest(req)
                sendTextMessage(req.chat, String.format(Constants.filteredSuspiciousUser, req.user.detailName))
                return@onChatJoinRequest
            }
        }

        createVerification(dao, req.chat, req.user)
    }

    onDataCallbackQuery(
        initialFilter = { ChangeQuestionCallback.isValid(it.data) }
    ) { query ->
        ChangeQuestionCallback.decode(query.data)?.let {
            it.mutex.withLock {
                if (!userPending.containsKey(it.token)) return@withLock
                if (it.chances > 0) {
                    if (it.sessionId == null || Captcha.getVerifyResult(it.sessionId!!) != "Pending") {
                        sendTextMessage(it.user, it.language.cannotChangeQuestion)
                        return@withLock
                    }
                    it.chances--
                    it.sessionId = null
                    editMessageText(
                        chat = it.user,
                        messageId = it.privateVerifyMessage.messageId,
                        text = String.format(it.language.privateVerifyMessage, it.user.fullName, it.dao.timeout, it.chances),
                        replyMarkup = inlineKeyboard {
                            row { webAppButton(it.language.startVerify, config.webApiUrl + "/captcha/?token=" + it.token) }
                            if (it.chances > 0) {
                                row { +CallbackDataInlineKeyboardButton(it.language.changeQuestion, ChangeQuestionCallback.encode(it.token)) }
                            }
                        }
                    )
                }
            }
        }
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
            approveChatJoinRequest(verification.chat, verification.user)
            sendTextMessage(verification.user, verification.language.manualPassPrivate)
            sendTextMessage(verification.chat, String.format(Constants.manualPassGroup, query.from.fullName, verification.user.fullName))
            verification.doClean(this)
        }
    }
}
