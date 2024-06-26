package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V24_KeyValueStore : Migration() {

    private object V24_KeyValueStore : Table("key_value_store") {
        var key = varchar("key", length = 10485760).uniqueIndex()
        var value = varchar("value", length = 10485760).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V24_KeyValueStore)
        }
    }
}
