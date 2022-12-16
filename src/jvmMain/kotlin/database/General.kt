package database

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object GeneralTable : IntIdTable(name = "general") {
    val item = text("item")
    val value = text("value")
}

class GeneralDao(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GeneralDao>(GeneralTable)

    val item by GeneralTable.item
    var value by GeneralTable.value
}

object GeneralKeys {
    const val CHATGPT_WHITELIST = "chatgpt_whitelist"
    const val CHATGPT_CENSORSHIP = "chatgpt_censorship"
}
