interface Constants {

    companion object {
        val help = """
            该 bot 具有以下功能：

            手性碳入群验证：如需使用请联系作者
            /rq   - 随机抽取一道 408 计算机考研选择题（实名投票）
            /rqa  - 随机抽取一道 408 计算机考研选择题（匿名投票）
            /qb   - 查看当前题池状态
            /rqb  - （管理员命令）立即刷新题池
            /jr   - 获取当前群入群验证设置
            /jr_retry <times> - 设置当前入群验证重试次数
            /jr_timeout <([0-9]*[dhm])*> - 设置当前入群验证超时时间
            /jr_fail2ban <([0-9]*[dhm])*> - 设置当前入群验证失败封禁时间
        """.trimIndent()

        const val groupVerifyMessage = "新成员 %s 正在进行入群验证"
        const val manualPass = "人工通过（管理员）"
        const val manualPassGroup = "管理员 %s 人工通过了 %s 的入群验证"
        const val passVerifyGroup = "新成员 %s 通过验证"
        const val failVerifyGroup = "%s 未通过验证，已被临时封禁"
        const val filteredSuspiciousUser = "可疑用户 %s 尝试加入群聊，已忽略"

        const val questionBankStatus = "当前题库中加载了 %d 道题，题池中剩余 %d 道题"
        const val notAdmin = "你不是该 bot 的主人"
        const val refreshQuestionBank = "已刷新题库，加载了 %d 道题"

        fun errorOccurred(e: Throwable) = "发生未知错误\n" + e.message?.replace(config.token, "******")
    }

    val privateVerifyMessage: String
    val startVerify: String
    val changeQuestion: String
    val cannotChangeQuestion: String
    val manualPassPrivate: String
    val passVerifyPrivate: String
    val failVerifyPrivate: String
    val errorVerifyPrivate: String
}

object Chinese : Constants {
    override val privateVerifyMessage = """
        新成员 %s，请在 %s 内完成验证码以证明你不是机器人
        你有 %d 次机会可以更换验证码
    """.trimIndent()

    override val startVerify = "开始验证"
    override val changeQuestion = "更换题目"
    override val cannotChangeQuestion = "您尚未加载验证码或已进行验证，无法更换题目"
    override val manualPassPrivate = "管理员人工通过了你的入群验证"
    override val passVerifyPrivate = "通过验证"
    override val failVerifyPrivate = "未通过验证，你将被封禁 %s"
    override val errorVerifyPrivate = "机器人发生故障，请稍后重新进行验证"
}

object English : Constants {
    override val privateVerifyMessage = """
        New member %s, please complete the captcha in %s to prove that you are not a robot
        You have %d chances to change the captcha
    """.trimIndent()

    override val startVerify = "Start verify"
    override val changeQuestion = "Change captcha"
    override val cannotChangeQuestion = "You haven't loaded the captcha or have challenged it. So you can't change now"
    override val manualPassPrivate = "The administrator manually passed your join request"
    override val passVerifyPrivate = "Verification passed"
    override val failVerifyPrivate = "Failed to pass the verification. You will be banned in %s"
    override val errorVerifyPrivate = "The verification bot goes wrong. Please verify again later"
}
