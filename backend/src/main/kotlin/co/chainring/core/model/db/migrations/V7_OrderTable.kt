package co.chainring.core.model.db.migrations

import co.chainring.core.db.Migration
import co.chainring.core.model.db.GUIDTable
import co.chainring.core.model.db.MarketTable
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.PGEnum
import co.chainring.core.model.db.enumDeclaration
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction

@Suppress("ClassName")
class V7_OrderTable : Migration() {

    @Serializable
    enum class V7_OrderType {
        Market,
        Limit,
    }

    @Serializable
    enum class V7_OrderSide {
        Buy,
        Sell,
    }

    @Serializable
    enum class V7_OrderStatus {
        Open,
        Partial,
        Filled,
        Cancelled,
        Expired,
    }

    object V7_OrderTable : GUIDTable<OrderId>("order", ::OrderId) {
        val nonce = varchar("nonce", 10485760).index()
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val marketGuid = reference("market_guid", MarketTable).index()
        val status = customEnumeration(
            "status",
            "OrderStatus",
            { value -> V7_OrderStatus.valueOf(value as String) },
            { PGEnum("OrderStatus", it) },
        ).index()
        val type = customEnumeration(
            "type",
            "OrderType",
            { value -> V7_OrderType.valueOf(value as String) },
            { PGEnum("OrderType", it) },
        )
        val side = customEnumeration(
            "side",
            "OrderSide",
            { value -> V7_OrderSide.valueOf(value as String) },
            { PGEnum("OrderSide", it) },
        )
        val amount = decimal("amount", 30, 18)
        val originalAmount = decimal("original_amount", 30, 18)
        val price = decimal("price", 30, 18).nullable()
        val updatedAt = timestamp("updated_at").nullable()
        val updatedBy = varchar("updated_by", 10485760).nullable()
        val closedAt = timestamp("closed_at").nullable()
        val closedBy = varchar("closed_by", 10485760).nullable()
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE OrderSide AS ENUM (${enumDeclaration<V7_OrderSide>()})")
            exec("CREATE TYPE OrderType AS ENUM (${enumDeclaration<V7_OrderType>()})")
            exec("CREATE TYPE OrderStatus AS ENUM (${enumDeclaration<V7_OrderStatus>()})")
            SchemaUtils.createMissingTablesAndColumns(V7_OrderTable)
        }
    }
}