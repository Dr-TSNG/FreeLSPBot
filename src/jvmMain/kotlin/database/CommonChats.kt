package database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

object CommonChatsTable : Table(name = "common_chats") {
    private val user = long("user")

    fun getCommonChatsForUserId(userId: Long) = transaction {
        CommonChatsTable.select {
            user eq userId
        }.count()
    }
}
