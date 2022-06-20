import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val token: String,
    val groupWhiteList: Set<Long>,
    val proxyHost: String,
    val proxyPort: Int
)
