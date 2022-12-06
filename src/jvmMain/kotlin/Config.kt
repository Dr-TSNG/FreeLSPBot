import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val proxyHost: String,
    val proxyPort: Int,
    val serverPort: Int,
    val database: String,
    val dbUsername: String,
    val dbPassword: String,
    val botApiUrl: String,
    val webApiUrl: String,
    val token: String,
    val owner: Long,
    val captchaApiUrl: String,
    val captchaApiKey: String
)
