package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketId
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V14_MarketTickSize : Migration() {

    object V14_MarketTable : GUIDTable<MarketId>("market", ::MarketId) {
        val tickSize = decimal("tick_size", 30, 18).nullable()
    }

    override fun run() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(V14_MarketTable)
            exec("""UPDATE market SET tick_size = '0.01'::numeric(30, 18) WHERE guid = 'USDC/DAI'""")
            exec("""UPDATE market SET tick_size = '0.05'::numeric(30, 18) WHERE tick_size IS NULL""")
            exec("""ALTER TABLE market ALTER COLUMN tick_size SET NOT NULL""")
        }
    }
}
