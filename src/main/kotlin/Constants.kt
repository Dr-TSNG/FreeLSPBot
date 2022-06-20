object Constants {

    val help = """
        该 bot 具有以下功能：
        
        /help - 显示此帮助信息
        /rq   - 随机抽取一道 408 计算机考研选择题
    """.trimIndent()

    val newMemberReply = """
        新成员 %s，请在 3 分钟内完成以下题目以证明你不是机器人
    """.trimIndent()

    const val changeQuestion = "换一道题"
    const val passVerification = "新成员 %s %s 通过验证"
    const val failVerification = "%s %s 未通过验证，已被移除群组"
}
