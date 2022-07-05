package function

import Constants
import Utils.detailName
import Utils.fullName
import Utils.kickUser
import com.soywiz.klock.DateTimeSpan
import config
import dev.inmo.tgbotapi.extensions.api.chat.members.restrictChatMember
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.send.media.sendPhoto
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.message
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.LeftRestrictionsChatPermissions
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import logger
import retry
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class NewChatMemberVerification private constructor(
    val userId: Long,
    var ans: String,
    var changesLeft: Int,
    val callback: suspend NewChatMemberVerification.(DataCallbackQuery) -> Unit
) {
    companion object {
        private val dataCallbackMap = ConcurrentHashMap<Long, NewChatMemberVerification>()

        context(BehaviourContext)
        suspend fun create(chat: Chat, user: User, changes: Int) {
            logger.debug("Create new verification for ${user.detailName} in chat ${chat.id.chatId}")
            val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val timeoutScope = CoroutineScope(Dispatchers.Unconfined)

            var (question, verifier) = retry {
                val q = CS408.pickUp()
                q to sendPhoto(
                    chat,
                    InputFile.fromFile(q.first),
                    String.format(Constants.newMemberReply, user.fullName, config.verifyTimeout, 3),
                    replyMarkup = CS408.replyMarkup
                )
            }.onFailure { e ->
                logger.error("Failed to send photo", e)
                sendMessage(chat, Constants.errorOccurred(e))
            }.invoke(Duration.ofSeconds(1))

            val timeout: suspend CoroutineScope.() -> Unit = {
                delay(Duration.ofMinutes(config.verifyTimeout.toLong()))
                withContext(singleThreadDispatcher) {
                    dataCallbackMap.remove(verifier.messageId)
                    kickUser(chat, user, true)
                    deleteMessage(verifier)
                }
            }
            timeoutScope.launch(block = timeout)

            dataCallbackMap[verifier.messageId] = NewChatMemberVerification(user.id.chatId, question.second, changes) {
                logger.debug("Answer from ${user.detailName}: ${it.data}")
                withContext(singleThreadDispatcher) {
                    when (it.data) {
                        Constants.changeQuestion -> {
                            if (changesLeft > 0) {
                                changesLeft--
                                val (newQuestion, newVerifier) = retry {
                                    val q = CS408.pickUp()
                                    q to sendPhoto(
                                        chat,
                                        InputFile.fromFile(q.first),
                                        String.format(
                                            Constants.newMemberReply,
                                            user.fullName,
                                            config.verifyTimeout,
                                            changesLeft
                                        ),
                                        replyMarkup = CS408.replyMarkup
                                    )
                                }.onFailure { e ->
                                    logger.error("Failed to send photo", e)
                                    sendMessage(chat, Constants.errorOccurred(e))
                                }.invoke(Duration.ofSeconds(1))

                                timeoutScope.coroutineContext.cancelChildren()
                                timeoutScope.launch(block = timeout)

                                deleteMessage(verifier)
                                dataCallbackMap.remove(verifier.messageId)
                                verifier = newVerifier
                                ans = newQuestion.second
                                dataCallbackMap[verifier.messageId] = this@NewChatMemberVerification
                            }
                        }
                        ans -> {
                            logger.info("Verified ${user.detailName}")
                            timeoutScope.cancel()
                            sendMessage(chat, String.format(Constants.passVerification, user.fullName))
                            restrictChatMember(chat.id, user, permissions = LeftRestrictionsChatPermissions)
                            deleteMessage(verifier)
                        }
                        else -> {
                            logger.info("Wrong answer from ${user.detailName}")
                            timeoutScope.cancel()
                            kickUser(chat, user, true, DateTimeSpan(minutes = config.verifyFailedBanTime))
                            deleteMessage(verifier)
                        }
                    }
                }
            }
        }

        context(BehaviourContext)
        fun filter(it: DataCallbackQuery): Boolean {
            val msg = it.message ?: return false
            return it.from.id.chatId == dataCallbackMap[msg.messageId]?.userId && config.groupWhiteList.contains(msg.chat.id.chatId)
        }

        context(BehaviourContext)
        suspend fun listen(it: DataCallbackQuery) {
            val v = dataCallbackMap[it.message!!.messageId] ?: return
            v.callback.invoke(v, it)
        }
    }
}
