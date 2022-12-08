package database

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object ChatGptTable : IntIdTable(name = "chat_gpt") {
    val user = long("user")
    val token = text("token")
    val used = integer("used")
}

class ChatGptDao(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ChatGptDao>(ChatGptTable)

    var user by ChatGptTable.user
    var token by ChatGptTable.token
    var used by ChatGptTable.used
}
