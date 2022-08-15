package function

import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.requests.abstracts.InputFile
import dev.inmo.tgbotapi.types.ChatId
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logger
import plugin.GooglePlay
import proxiedHttpClient
import java.io.File
import java.net.URLClassLoader
import kotlin.time.Duration

@Serializable
private class Config(
    val patcher: String,
    val module: String,
    val channel: Long,
    var twitterVersion: Int
)

private const val twitter = "com.twitter.android"
private const val tmpDir = "data/TwiFucker/tmp"
private const val configFile = "data/TwiFucker/config.json"

context(BehaviourContext)
fun installTwiFucker() {
    launch(Dispatchers.IO) {
        val googlePlay = GooglePlay.create().getOrElse {
            logger.error("Google Play API is not available", it)
            return@launch
        }
        logger.info("Google Play API initialized")
        while (true) {
            suspend fun block() {
                logger.debug("Check for new Twitter version")
                val config = Json.decodeFromString<Config>(File(configFile).readText())
                val app = googlePlay.getAppInfo(twitter).getOrElse {
                    logger.error("Failed to get app info for $twitter", it)
                    return@block
                }
                if (app.versionCode <= config.twitterVersion) return
                logger.info(String.format("New version of Twitter detected: %s (%d)", app.versionName, app.versionCode))
                val files = googlePlay.downloadApp(app).getOrElse {
                    logger.error(String.format("Failed to download Twitter version %s (%d)", app.versionName, app.versionCode), it)
                    return@block
                }
                if (files.size > 1) {
                    logger.error("More than one apks")
                    return
                }

                logger.debug("Download Twitter apk")
                File(tmpDir).apply {
                    deleteRecursively()
                    mkdir()
                }
                val response = proxiedHttpClient.get(files[0].url)
                if (response.status != HttpStatusCode.OK) {
                    val error = String(response.readBytes())
                    logger.error("Failed to download file ${files[0].name}: $error")
                    return
                }
                File("$tmpDir/${files[0].name}").writeBytes(response.body())
                logger.debug("Downloaded file ${files[0].name}")

                logger.debug("Do LSPatch")
                val patcher = File("data/TwiFucker/${config.patcher}").toURI().toURL()
                val cl = URLClassLoader(arrayOf(patcher), Thread.currentThread().contextClassLoader)
                val clazz = cl.loadClass("org.lsposed.patch.LSPatch")
                val main = clazz.getMethod("main", Array<String>::class.java)
                val args = arrayOf(
                    "$tmpDir/${files[0].name}",
                    "-m", "data/TwiFucker/${config.module}",
                    "-l", "2",
                    "-o", tmpDir
                )
                main.invoke(null, args)
                val patched = File(tmpDir)
                    .listFiles { _, name -> name.endsWith("lspatched.apk") }
                    ?.singleOrNull()
                if (patched == null) {
                    logger.error("Failed to patch Twitter")
                    return
                }

                logger.debug("Post to channel")
                runCatching {
                    sendDocument(
                        ChatId(config.channel),
                        InputFile(patched),
                        text = String.format("Twitter %s (%d)", app.versionName, app.versionCode)
                    )
                }.onFailure { e ->
                    if (e is SocketTimeoutException && e.cause?.cause?.message == "Socket closed") return@onFailure
                    // TODO: It is strange, but it does work.
                    logger.error("Failed to post to channel", e)
                    return@block
                }

                logger.info("Posted new patched Twitter apk")
                config.twitterVersion = app.versionCode
                File(configFile).writeText(Json.encodeToString(config))
            }
            block()
            delay(Duration.parse("1h"))
        }
    }
}
