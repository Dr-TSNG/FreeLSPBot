import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val proxyHost: String,
    val proxyPort: Int,
    val serverHost: String,
    val serverPort: Int,
    val database: String,
    val dbUsername: String,
    val dbPassword: String,
    val botApiUrl: String,
    val webApiUrl: String,
    val token: String,
    val admin: Long,
    val captchaApiUrl: String,
    val captchaApiKey: String
)
