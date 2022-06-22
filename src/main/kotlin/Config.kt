import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val proxyHost: String,
    val proxyPort: Int,
    val token: String,
    val groupWhiteList: Set<Long>,
    val verifyTimeout: Int,
    val verifyFailedBanTime: Int,
)
