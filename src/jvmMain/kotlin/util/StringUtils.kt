package util

object StringUtils {

    fun getRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    val String.isAllChinese
        get() = matches(Regex("[\\u4e00-\\u9fa5，]+"))
}
