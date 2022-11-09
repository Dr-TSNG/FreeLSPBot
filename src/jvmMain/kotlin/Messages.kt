object Messages {

    val help = """
        该 bot 具有以下功能：
        /rq   - 随机抽取一道 408 计算机考研选择题（实名投票）
        /rqa  - 随机抽取一道 408 计算机考研选择题（匿名投票）
        /qb   - 查看当前题池状态
        /rqb  - （管理员命令）立即刷新题池

        手性碳入群验证：如需使用请联系作者：
        /jrctl <on/off> - 开启/关闭入群验证
        /jr_info - 获取当前群入群验证设置
        /jr_statistics - 获取当前群入群验证统计
        /jr_timeout <1~10m> - 设置当前入群验证超时时间（最少 1m，最多 10m）
        /jr_fail2ban <([0-9]*[dhm])*> - 设置当前入群验证失败封禁时间（最少 1m）
        /jr_namemask <on/off> - 开启/关闭用户名打码
        /jr_regexban <regex/off> - 设置用户名封禁规则（off 关闭）
        /jr_logthread <thread_id> - 设置日志话题（若群未启用话题功能则输入 0）
    """.trimIndent()

    const val cmdIllegalArgument = "命令参数错误"
    const val cmdGroupOnly = "该指令只能在群内使用"
    const val cmdOwnerOnly = "该指令只能由机器人主人使用"
    const val cmdGroupNotInWhiteList = "该群不在白名单中，如需使用请联系 bot 主人"

    const val verifyManualAccept = "人工通过（管理员）"
    const val verifyManualDecline = "人工拒绝（管理员）"
    const val verifyEnabled = "入群验证已启用"
    const val verifyDisabled = "入群验证已禁用"
    const val verifyTimeoutLimit = "入群验证超时时间应不少于 1 分钟且不超过 1 小时"
    const val verifyFail2BanLimit = "入群验证失败封禁时间应不少于 1 分钟"
    const val verifyNameMaskEnabled = "用户名打码已启用"
    const val verifyNameMaskDisabled = "用户名打码已禁用"
    const val verifyRegexInvalid = "无效的正则表达式"

    fun errorOccurred(e: Throwable) = "发生未知错误\n" + e.message?.replace(config.token, "******")
    fun cmdAdminRequired(permission: String) = "该指令仅限具有 $permission 权限的管理员才能使用"

    fun verifyNew(user: String) = "新成员 $user 正在进行入群验证"
    fun verifyPassed(user: String) = "新成员 $user 通过验证"
    fun verifyFailed(user: String) = "$user 未通过验证，已被临时封禁"
    fun verifyIgnored(user: String) = "可疑用户 $user 尝试加入群聊，已忽略"
    fun verifyRegexBanned(user: String) = "用户 $user 尝试加入群聊，根据用户名正则规则封禁"
    fun verifyManualAccepted(admin: String, user: String) = "管理员 $admin 通过了 $user 的入群验证"
    fun verifyManualDeclined(admin: String, user: String) = "管理员 $admin 拒绝了 $user 的入群验证"
    fun verifySettings(enabled: Boolean, timeout: String, fail2ban: String, regexBan: String) = buildString {
        appendLine("当前入群验证设置：")
        appendLine("已启用：$enabled")
        appendLine("超时时间：$timeout")
        appendLine("失败封禁时间：$fail2ban")
        appendLine("正则用户名封禁：$regexBan")
    }
    fun verifyStatistics(total: Int, passed: Int) = buildString {
        appendLine("当前群聊共进行 $total 次验证，通过 $passed 次")
        appendLine("通过率：%.2f%%".format(passed.toDouble() / total * 100))
    }
    fun verifySetTimeout(timeout: String) = "入群验证超时时间已设置为 $timeout"
    fun verifySetFail2Ban(fail2ban: String) = "入群验证失败封禁时间已设置为 $fail2ban"
    fun verifySetRegexBan(regexBan: String) = "入群验证用户名封禁规则" + if (regexBan == "off") "已关闭" else "已设置为 $regexBan"
    fun verifySetLogThread(threadId: Long) = "入群验证日志话题已设置为 $threadId"

    fun cs408PoolStatus(total: Int, remaining: Int) = "当前题库中加载了 $total 道题，题池中剩余 $remaining 道题"
    fun cs408RefreshPool(total: Int) = "已刷新题库，加载了 $total 道题"

    sealed interface Private {
        val verifyStart: String
        val verifyPassed: String
        val verifyError: String
        val verifyManualAccepted: String

        fun verifyDesc(user: String, timeout: String): String
        fun verifyFailed(fail2ban: String): String
    }

    object Chinese : Private {
        override val verifyStart = "开始验证"
        override val verifyPassed = "通过验证"
        override val verifyError = "机器人发生故障，请稍后重新进行验证"
        override val verifyManualAccepted = "管理员人工通过了你的入群验证"

        override fun verifyDesc(user: String, timeout: String) = "新成员 $user，请在 $timeout 内完成验证码以证明你不是机器人"
        override fun verifyFailed(fail2ban: String) = "未通过验证，请 $fail2ban 后再次尝试"
    }

    object English : Private {
        override val verifyStart = "Start verify"
        override val verifyPassed = "Verification passed"
        override val verifyError = "The verification bot goes wrong. Please verify again later"
        override val verifyManualAccepted = "The administrator manually passed your join request"

        override fun verifyDesc(user: String, timeout: String) = "New member $user, please complete the captcha in $timeout to prove that you are not a robot"
        override fun verifyFailed(fail2ban: String) = "Failed to pass the verification. Please try again after $fail2ban"
    }
}
