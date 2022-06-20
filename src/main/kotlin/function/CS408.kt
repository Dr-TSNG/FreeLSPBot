package function

import Constants
import dev.inmo.tgbotapi.extensions.utils.types.buttons.inlineKeyboard
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.utils.row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import logger
import java.io.File

object CS408 {

    @Serializable
    private data class QuestionSet(
        val dir: String,
        val questions: List<String>
    )

    @Serializable
    private data class All(
        val collections: List<QuestionSet>
    )

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


    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun pickUp(): Pair<File, String> =
        withContext(Dispatchers.IO.limitedParallelism(1)) {
            if (pool.isEmpty()) {
                logger.info("Question pool is empty")
                val all = Json.decodeFromString<All>(File("data/408/collections.json").readText())
                pool = all.collections.flatMap {
                    it.questions.mapIndexed { index, ans ->
                        File("data/408/${it.dir}/${index + 1}.png") to ans
                    }
                }.toMutableSet()
                logger.info("Refilled pool with ${pool.size} questions")
            }
            val r = pool.random()
            pool -= r
            return@withContext r
        }
}
