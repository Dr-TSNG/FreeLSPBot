package function

import Messages
import config
import dev.inmo.tgbotapi.extensions.api.send.media.sendPhoto
import dev.inmo.tgbotapi.extensions.api.send.polls.sendQuizPoll
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.utils.extensions.raw.from
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.utils.RiskFeature
import logger
import plugin.CS408
import util.BotUtils.detailName

context(BehaviourContext)
@OptIn(RiskFeature::class)
suspend fun installCS408() {
    onCommand("qb") {
        logger.debug("/qb command from ${it.from?.detailName}")
        val (bankSize, poolSize) = CS408.getBankStatus()
        sendMessage(it.chat, Messages.cs408PoolStatus(bankSize, poolSize))
    }

    onCommand("rqb") {
        logger.debug("/rqb command from ${it.from?.detailName}")
        if (it.from?.id?.chatId != config.owner) {
            sendMessage(it.chat, Messages.cmdOwnerOnly)
        } else {
            CS408.refreshPool()
            val (bankSize, _) = CS408.getBankStatus()
            sendMessage(it.chat, Messages.cs408RefreshPool(bankSize))
        }
    }

    onCommand("rq") {
        logger.debug("/rq command from ${it.from?.detailName}")
        runCatching {
            val (photo, ans) = CS408.pickUp()
            sendPhoto(it.chat, InputFile(photo))
            sendQuizPoll(it.chat, "选择正确的选项", listOf("A", "B", "C", "D"), ans[0] - 'A', false)
        }.onFailure { e ->
            logger.error("/rq command error!", e)
            sendMessage(it.chat, Messages.errorOccurred(e))
        }
    }

    onCommand("rqa") {
        logger.debug("/rqa command from ${it.from?.detailName}")
        runCatching {
            val (photo, ans) = CS408.pickUp()
            sendPhoto(it.chat, InputFile(photo))
            sendQuizPoll(it.chat, "选择正确的选项", listOf("A", "B", "C", "D"), ans[0] - 'A', true)
        }.onFailure { e ->
            logger.error("/rqa command error!", e)
            sendMessage(it.chat, Messages.errorOccurred(e))
        }
    }
}
