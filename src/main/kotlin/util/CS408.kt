package util

import Constants
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import logger
import withReentrantLock
import java.io.File

object CS408 {

    @Serializable
    private data class QuestionSet(
        val dir: String,
        val questions: List<String?>
    )

    @Serializable
    private data class All(
        val collections: List<QuestionSet>
    )

    private val mutex = Mutex()
    private var bankSize = 0
    private var pool = mutableSetOf<Pair<File, String>>()

    val replyMarkup = inlineKeyboard {
        row { +CallbackDataInlineKeyboardButton(Constants.changeQuestion, Constants.changeQuestion) }
        row {
            +CallbackDataInlineKeyboardButton("A", "A")
            +CallbackDataInlineKeyboardButton("B", "B")
            +CallbackDataInlineKeyboardButton("C", "C")
            +CallbackDataInlineKeyboardButton("D", "D")
        }
    }

    fun getBankStatus() = bankSize to pool.size

    suspend fun refreshPool() {
        mutex.withReentrantLock {
            withContext(Dispatchers.IO) {
                val all = Json.decodeFromString<All>(File("data/408/collections.json").readText())
                pool = all.collections.flatMap {
                    it.questions.mapIndexedNotNull { index, ans ->
                        if (ans == null) null
                        else File("data/408/${it.dir}/${index + 1}.png") to ans
                    }
                }.toMutableSet()
                bankSize = pool.size
            }
        }
    }

    suspend fun pickUp(): Pair<File, String> =
        mutex.withReentrantLock {
            if (pool.isEmpty()) {
                logger.info("Question pool is empty")
                refreshPool()
                logger.info("Refilled pool with $bankSize questions")
            }
            val r = pool.random()
            pool -= r
            logger.debug("pick up question ${r.first.path}")
            return@withReentrantLock r
        }
}
