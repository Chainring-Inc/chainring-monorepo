package co.chainring.core.model.db

import co.chainring.apps.api.model.Trade
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import de.fxlae.typeid.TypeId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.math.BigInteger

@Serializable
@JvmInline
value class ExecutionId(override val value: String) : EntityId {
    companion object {
        fun generate(): ExecutionId = ExecutionId(TypeId.generate("execution").toString())
    }

    override fun toString(): String = value
}

@Serializable
enum class ExecutionRole {
    Taker,
    Maker,
}

object OrderExecutionTable : GUIDTable<ExecutionId>("order_execution", ::ExecutionId) {
    val createdAt = timestamp("created_at")
    val timestamp = timestamp("timestamp")
    val orderGuid = reference("order_guid", OrderTable).index()
    val tradeGuid = reference("trade_guid", TradeTable).index()
    val role = customEnumeration(
        "role",
        "ExecutionRole",
        { value -> ExecutionRole.valueOf(value as String) },
        { PGEnum("ExecutionRole", it) },
    ).index()
    val feeAmount = decimal("fee_amount", 30, 0)
    val feeSymbol = varchar("fee_symbol", 10485760)
}

class OrderExecutionEntity(guid: EntityID<ExecutionId>) : GUIDEntity<ExecutionId>(guid) {
    fun toTradeResponse(): Trade {
        return Trade(
            id = this.trade.guid.value,
            orderId = this.orderGuid.value,
            marketId = this.order.marketGuid.value,
            timestamp = this.timestamp,
            side = this.order.side,
            amount = this.trade.amount,
            price = this.trade.price,
            feeAmount = this.feeAmount,
            feeSymbol = this.feeSymbol,
        )
    }

    companion object : EntityClass<ExecutionId, OrderExecutionEntity>(OrderExecutionTable) {
        fun create(
            timestamp: Instant,
            orderEntity: OrderEntity,
            tradeEntity: TradeEntity,
            role: ExecutionRole,
            feeAmount: BigInteger,
            feeSymbol: Symbol,
        ) = OrderExecutionEntity.new(ExecutionId.generate()) {
            val now = Clock.System.now()
            this.createdAt = now
            this.timestamp = timestamp
            this.order = orderEntity
            this.trade = tradeEntity
            this.role = role
            this.feeAmount = feeAmount
            this.feeSymbol = feeSymbol
        }

        fun findForOrder(orderEntity: OrderEntity): List<OrderExecutionEntity> {
            return OrderExecutionEntity.find {
                OrderExecutionTable.orderGuid.eq(orderEntity.guid)
            }.toList()
        }

        fun listExecutions(ownerAddress: Address, beforeTimestamp: Instant, limit: Int): List<OrderExecutionEntity> {
            return OrderExecutionTable
                .join(OrderTable, JoinType.INNER, OrderTable.guid, OrderExecutionTable.orderGuid)
                .join(TradeTable, JoinType.INNER, TradeTable.guid, OrderExecutionTable.tradeGuid)
                .select(OrderExecutionTable.columns)
                .where {
                    OrderTable.ownerAddress.eq(ownerAddress.value) and OrderExecutionTable.timestamp.less(beforeTimestamp)
                }
                .limit(limit)
                .map {
                    OrderExecutionEntity.wrapRow(it)
                }.toList()
        }
    }

    var createdAt by OrderExecutionTable.createdAt
    var timestamp by OrderExecutionTable.timestamp
    var orderGuid by OrderExecutionTable.orderGuid
    var order by OrderEntity referencedOn OrderExecutionTable.orderGuid
    var tradeGuid by OrderExecutionTable.tradeGuid
    var trade by TradeEntity referencedOn OrderExecutionTable.tradeGuid
    var role by OrderExecutionTable.role
    var feeAmount by OrderExecutionTable.feeAmount.transform(
        toReal = { it.toBigInteger() },
        toColumn = { it.toBigDecimal() },
    )
    var feeSymbol by OrderExecutionTable.feeSymbol.transform(
        toReal = { Symbol(it) },
        toColumn = { it.value },
    )
}
