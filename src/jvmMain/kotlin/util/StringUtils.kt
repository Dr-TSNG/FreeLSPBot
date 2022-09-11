package util

object StringUtils {

    fun getRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    val String.isAllChineseOrAlphabet
        get() = matches(Regex("[a-zA-Z\\u4e00-\\u9fa5，]+"))
}
