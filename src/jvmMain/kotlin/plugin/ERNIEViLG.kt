package plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logger
import util.StringUtils.isAllChineseOrAlphabet
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.io.path.createTempDirectory

object ERNIEViLG {

    private val types = setOf("水彩", "油画", "粉笔画", "卡通", "蜡笔画", "儿童画", "探索无限")

    private fun buildCmd(path: String, type: String, keyword: String) = arrayOf(
        "docker", "run", "--rm",
        "-v", "$path:/paddle",
        "drtsng/ernie-vilg:2.3.2-1.0.0",
        "hub", "run", "ernie_vilg",
        "--style", type,
        "--text_prompts", keyword,
        "--output_dir", "/paddle"
    )

    @Throws(IllegalArgumentException::class)
    suspend fun generate(type: String, keyword: String): Array<File>? {
        return withContext(Dispatchers.IO) {
            suspendCoroutine {
                val tmpDir = createTempDirectory("paddle")
                if (type !in types) {
                    it.resumeWithException(IllegalArgumentException("不支持的类型：$type"))
                    return@suspendCoroutine
                }
                if (!keyword.isAllChineseOrAlphabet) {
                    it.resumeWithException(IllegalArgumentException("仅支持中文和英文字符"))
                    return@suspendCoroutine
                }
                val cmd = buildCmd(tmpDir.toString(), type, keyword)
                logger.debug("Generate images with command: " + cmd.joinToString(" "))
                Runtime.getRuntime().exec(cmd).waitFor()
                it.resume(tmpDir.toFile().listFiles())
            }
        }
    }
}
