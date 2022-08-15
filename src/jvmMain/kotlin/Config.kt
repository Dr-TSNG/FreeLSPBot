import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val proxyHost: String,
    val proxyPort: Int,
    val serverHost: String,
    val serverPort: Int,
    val botApiUrl: String,
    val webApiUrl: String,
    val token: String,
    val admin: Long,
    val groupWhiteList: Set<Long>,
    val captchaApiUrl: String,
    val changeCaptchaChances: Int,
    val verifyTimeout: String,
    val verifyFail2Ban: String
)
