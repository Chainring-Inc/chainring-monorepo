package co.chainring.apps.api.model

import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals

class BatchOrderSerializerTest {

    private val createMarketOrderRequest = CreateOrderApiRequest.Market(
        nonce = "123",
        marketId = MarketId("BTC/ETH"),
        side = OrderSide.Buy,
        amount = BigInteger("100000"),
    )

    private val updateLimitOrderRequest = UpdateOrderApiRequest.Limit(
        id = OrderId.generate(),
        amount = BigInteger("1000"),
        price = BigDecimal("101000"),
    )

    @Test
    fun `test decode`() {
        val createOrderString = Json.encodeToString(createMarketOrderRequest as CreateOrderApiRequest)
        val restoredOrder = Json.decodeFromJsonElement<CreateOrderApiRequest>(Json.parseToJsonElement(createOrderString))
        assertEquals(createMarketOrderRequest, restoredOrder)

        val batchOrderApiRequest = BatchOrdersApiRequest(
            createOrders = listOf(createMarketOrderRequest, createMarketOrderRequest),
            updateOrders = listOf(updateLimitOrderRequest),
            deleteOrders = emptyList(),
        )
        val batchOrderString = Json.encodeToString(batchOrderApiRequest)
        val restoredBatchOrder = Json.decodeFromJsonElement<BatchOrdersApiRequest>(Json.parseToJsonElement(batchOrderString))
        assertEquals(batchOrderApiRequest, restoredBatchOrder)
    }
}
