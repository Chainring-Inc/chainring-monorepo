package co.chainring.apps.api.model

import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

object Order {

    @Serializable
    data class Execution(
        val timestamp: Instant,
        val amount: BigIntegerJson,
        val price: BigIntegerJson,
        val role: ExecutionRole,
        val feeAmount: BigIntegerJson,
        val feeSymbol: Symbol,
    )

    @Serializable
    data class Timing(
        val createdAt: Instant,
        val updatedAt: Instant? = null,
        val closedAt: Instant? = null,
    )
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class CreateOrderApiRequest {
    abstract val nonce: String
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: BigIntegerJson

    @Serializable
    @SerialName("market")
    data class Market(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
    ) : CreateOrderApiRequest()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val nonce: String,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        val price: BigIntegerJson,
    ) : CreateOrderApiRequest()
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class UpdateOrderApiRequest {
    abstract val id: OrderId
    abstract val amount: BigIntegerJson

    @Serializable
    @SerialName("market")
    data class Market(
        override val id: OrderId,
        override val amount: BigIntegerJson,
    ) : UpdateOrderApiRequest()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val id: OrderId,
        override val amount: BigIntegerJson,
        val price: BigIntegerJson,
    ) : UpdateOrderApiRequest()
}

@Serializable
data class DeleteUpdateOrderApiRequest(
    val orderId: OrderId,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class OrderApiResponse {
    abstract val id: OrderId
    abstract val status: OrderStatus
    abstract val marketId: MarketId
    abstract val side: OrderSide
    abstract val amount: BigIntegerJson
    abstract val originalAmount: BigIntegerJson
    abstract val executions: List<Order.Execution>
    abstract val timing: Order.Timing

    @Serializable
    @SerialName("market")
    data class Market(
        override val id: OrderId,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val originalAmount: BigIntegerJson,
        override val executions: List<Order.Execution>,
        override val timing: Order.Timing,
    ) : OrderApiResponse()

    @Serializable
    @SerialName("limit")
    data class Limit(
        override val id: OrderId,
        override val status: OrderStatus,
        override val marketId: MarketId,
        override val side: OrderSide,
        override val amount: BigIntegerJson,
        override val originalAmount: BigIntegerJson,
        val price: BigIntegerJson,
        override val executions: List<Order.Execution>,
        override val timing: Order.Timing,
    ) : OrderApiResponse()
}

@Serializable
data class BatchOrdersApiRequest(
    val createOrders: List<CreateOrderApiRequest>,
    val updateOrders: List<UpdateOrderApiRequest>,
    val deleteOrders: List<DeleteUpdateOrderApiRequest>,
)

@Serializable
data class OrdersApiResponse(
    val orders: List<OrderApiResponse>,
)

@Serializable
@SerialName("OrderBook")
data class OrderBook(
    val marketId: MarketId,
    val buy: List<OrderBookEntry>,
    val sell: List<OrderBookEntry>,
    val last: LastTrade,
) : Publishable()

@Serializable
data class OrderBookEntry(
    val price: String,
    val size: BigDecimalJson,
)

@Serializable
enum class LastTradeDirection {
    Up,
    Down,
}

@Serializable
data class LastTrade(
    val price: String,
    val direction: LastTradeDirection,
)
