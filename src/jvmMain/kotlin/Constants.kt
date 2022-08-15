object Constants {

    val help = """
        该 bot 具有以下功能：
        
        /rq   - 随机抽取一道 408 计算机考研选择题（实名投票）
        /rqa  - 随机抽取一道 408 计算机考研选择题（匿名投票）
        /qb   - 查看当前题池状态
        /rqb  - （管理员命令）立即刷新题池
    """.trimIndent()

    val privateVerifyMessage = """
        新成员 %s，请在 %s 内完成以下题目以证明你不是机器人
        你有 %d 次机会可以更换题目
    """.trimIndent()
    const val groupVerifyMessage = "新成员 %s 正在进行入群认证"

    const val startVerify = "开始验证"
    const val manualPass = "人工通过（管理员）"
    const val manualPassPrivate = "管理员人工通过了你的入群验证"
    const val manualPassGroup = "管理员 %s 人工通过了 %s 的入群验证"
    const val passVerifyPrivate = "通过验证"
    const val passVerifyGroup = "新成员 %s 通过验证"
    const val failVerifyPrivate = "未通过验证，你将被封禁 %s"
    const val failVerifyGroup = "%s 未通过验证，已被临时封禁"

    const val questionBankStatus = "当前题库中加载了 %d 道题，题池中剩余 %d 道题"
    const val notAdmin = "你不是该 bot 的主人"
    const val refreshQuestionBank = "已刷新题库，加载了 %d 道题"

    fun errorOccurred(e: Throwable) = "发生未知错误\n" + e.message?.replace(config.token, "******")
}
