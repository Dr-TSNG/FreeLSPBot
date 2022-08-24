package database

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable

object JoinRequestTable : LongIdTable(name = "join_request", columnName = "chat") {
    val method = varchar("method", 10)
    val retryChances = byte("retry_chances")
    val timeout = varchar("timeout", 10)
    val fail2ban = varchar("fail2ban", 10)
    val commonChatLeast = byte("common_chat_least").nullable()
    val commonChatEasy = byte("common_chat_easy").nullable()
    val remark = varchar("remark", 10).nullable()
}

class JoinRequestDao(chat: EntityID<Long>) : LongEntity(chat) {
    companion object : LongEntityClass<JoinRequestDao>(JoinRequestTable)

    val method by JoinRequestTable.method
    val retryChances by JoinRequestTable.retryChances
    val timeout by JoinRequestTable.timeout
    val fail2ban by JoinRequestTable.fail2ban
    val commonChatLeast by JoinRequestTable.commonChatLeast
    val commonChatEasy by JoinRequestTable.commonChatEasy
    val remark by JoinRequestTable.remark
}
