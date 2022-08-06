import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val proxyHost: String,
    val proxyPort: Int,
    val token: String,
    val apiUrl: String,
    val admin: Long,
    val groupWhiteList: Set<Long>,
    val verifyTimeout: Int,
    val verifyFailedBanTime: Int,
)
