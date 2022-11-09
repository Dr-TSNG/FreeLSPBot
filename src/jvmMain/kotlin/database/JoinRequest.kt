package database

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable

object JoinRequestTable : LongIdTable(name = "join_request", columnName = "chat") {
    val enabled = bool("enabled")
    val timeout = varchar("timeout", 10)
    val fail2ban = varchar("fail2ban", 10)
    val nameMask = bool("name_mask")
    val regexBan = text("regex_ban").nullable()
    val logThread = long("log_thread").nullable()
    val commonChatLeast = byte("common_chat_least").nullable()
    val commonChatEasy = byte("common_chat_easy").nullable()
    val total = integer("total")
    val accepted = integer("accepted")
}

class JoinRequestDao(chat: EntityID<Long>) : LongEntity(chat) {
    companion object : LongEntityClass<JoinRequestDao>(JoinRequestTable)

    var enabled by JoinRequestTable.enabled
    var timeout by JoinRequestTable.timeout
    var fail2ban by JoinRequestTable.fail2ban
    var nameMask by JoinRequestTable.nameMask
    var regexBan by JoinRequestTable.regexBan
    var logThread by JoinRequestTable.logThread
    val commonChatLeast by JoinRequestTable.commonChatLeast
    val commonChatEasy by JoinRequestTable.commonChatEasy
    var total by JoinRequestTable.total
    var accepted by JoinRequestTable.accepted
}
