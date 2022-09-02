package database

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object LogTable : LongIdTable(name = "log") {
    val type = text("type")
    val date = datetime("date")
    val chat = long("chat").nullable()
    val user = long("user").nullable()
    val admin = long("admin").nullable()
    val level = varchar("level", 5)
    val message = text("message")
}

class LogDao(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<LogDao>(LogTable)

    var type by LogTable.type
    var date by LogTable.date
    var chat by LogTable.chat
    var user by LogTable.user
    var admin by LogTable.admin
    var level by LogTable.level
    var message by LogTable.message
}
