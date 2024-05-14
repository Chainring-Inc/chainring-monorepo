package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.LastTradeDirection
import co.chainring.apps.api.model.websocket.OHLC
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookEntry
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.client.ws.subscribeToLimits
import co.chainring.core.client.ws.subscribeToOrderBook
import co.chainring.core.client.ws.subscribeToOrders
import co.chainring.core.client.ws.subscribeToPrices
import co.chainring.core.client.ws.subscribeToTrades
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.TradeTable
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.generateOrderNonce
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalances
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.integrationtests.utils.assertError
import co.chainring.integrationtests.utils.assertLimitsMessageReceived
import co.chainring.integrationtests.utils.assertOrderBookMessageReceived
import co.chainring.integrationtests.utils.assertOrderCreatedMessageReceived
import co.chainring.integrationtests.utils.assertOrderUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertOrdersMessageReceived
import co.chainring.integrationtests.utils.assertPricesMessageReceived
import co.chainring.integrationtests.utils.assertTradeCreatedMessageReceived
import co.chainring.integrationtests.utils.assertTradeUpdatedMessageReceived
import co.chainring.integrationtests.utils.assertTradesMessageReceived
import kotlinx.datetime.Clock
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@ExtendWith(AppUnderTestRunner::class)
class OrderRoutesApiTest {
    private val btcEthMarketId = MarketId("BTC/ETH")
    private val btcUsdcMarketId = MarketId("BTC/USDC")
    private val usdcDaiMarketId = MarketId("USDC/DAI")

    @Test
    fun `CRUD order`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        var wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        val initialOrdersOverWs = wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        wsClient.subscribeToLimits(usdcDaiMarketId)
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        Faucet.fund(wallet.address)
        wallet.mintERC20("DAI", wallet.formatAmount("14", "DAI"))

        val amountToDeposit = wallet.formatAmount("14", "DAI")
        deposit(wallet, "DAI", amountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit)))

        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(amountToDeposit, msg.quote)
        }

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateOrderNonce(),
            marketId = usdcDaiMarketId,
            side = OrderSide.Buy,
            amount = wallet.formatAmount("1", "USDC"),
            price = BigDecimal("2"),
            signature = EvmSignature.emptySignature(),
        ).let {
            wallet.signOrder(it)
        }

        val createLimitOrderResponse = apiClient.createOrder(limitOrderApiRequest)

        // order created correctly
        assertIs<CreateOrderApiRequest.Limit>(createLimitOrderResponse.order)
        assertEquals(createLimitOrderResponse.order.marketId, limitOrderApiRequest.marketId)
        assertEquals(createLimitOrderResponse.order.side, limitOrderApiRequest.side)
        assertEquals(createLimitOrderResponse.order.amount, limitOrderApiRequest.amount)
        assertEquals(0, (createLimitOrderResponse.order as CreateOrderApiRequest.Limit).price.compareTo(limitOrderApiRequest.price))

        // client is notified over websocket
        wsClient.assertOrderCreatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(createLimitOrderResponse, msg.order as Order.Limit, false)
            validateNonceAndSignatureStored(createLimitOrderResponse.orderId, limitOrderApiRequest.nonce, limitOrderApiRequest.signature)
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(BigInteger("13999999999998000000"), msg.quote)
        }
        wsClient.close()

        // check that order is included in the orders list sent via websocket
        wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        assertEquals(
            listOf(createLimitOrderResponse.orderId) + initialOrdersOverWs.map { it.id },
            wsClient.assertOrdersMessageReceived().orders.map { it.id },
        )

        wsClient.subscribeToLimits(usdcDaiMarketId)
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        // update order
        val updateOrderApiRequest = UpdateOrderApiRequest(
            orderId = createLimitOrderResponse.orderId,
            marketId = createLimitOrderResponse.order.marketId,
            side = createLimitOrderResponse.order.side,
            amount = wallet.formatAmount("3", "USDC"),
            price = BigDecimal("2.01"),
            nonce = generateOrderNonce(),
            signature = EvmSignature.emptySignature(),
        ).let {
            wallet.signOrder(it)
        }
        val updatedOrderApiResponse = apiClient.updateOrder(
            updateOrderApiRequest,
        )
        assertEquals(updatedOrderApiResponse.requestStatus, RequestStatus.Accepted)
        assertIs<UpdateOrderApiRequest>(updatedOrderApiResponse.order)
        assertEquals(wallet.formatAmount("3", "USDC"), updatedOrderApiResponse.order.amount)
        assertEquals(0, BigDecimal("2.01").compareTo(updatedOrderApiResponse.order.price))
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(updatedOrderApiResponse.order, msg.order as Order.Limit, true)
            validateNonceAndSignatureStored(createLimitOrderResponse.orderId, updateOrderApiRequest.nonce, updateOrderApiRequest.signature)
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(BigInteger("13999999999993970000"), msg.quote)
        }

        // cancel order is idempotent
        apiClient.cancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse.orderId,
                marketId = createLimitOrderResponse.order.marketId,
                amount = createLimitOrderResponse.order.amount,
                side = createLimitOrderResponse.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signCancelOrder(it)
            },
        )
        wsClient.assertOrderUpdatedMessageReceived { msg ->
            assertEquals(createLimitOrderResponse.orderId, msg.order.id)
            assertEquals(OrderStatus.Cancelled, msg.order.status)
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId) { msg ->
            assertEquals(BigInteger("0"), msg.base)
            assertEquals(BigInteger("14000000000000000000"), msg.quote)
        }
        val cancelledOrder = apiClient.getOrder(createLimitOrderResponse.orderId)
        assertEquals(OrderStatus.Cancelled, cancelledOrder.status)

        wsClient.close()
    }

    @Test
    fun `CRUD order error cases`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        wsClient.assertOrdersMessageReceived()

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("20", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        deposit(wallet, "DAI", amountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit)))

        // operation on non-existent order
        apiClient.tryGetOrder(OrderId.generate())
            .assertError(ApiError(ReasonCode.OrderNotFound, "Requested order does not exist"))

        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = OrderId.generate(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = BigDecimal("3").toFundamentalUnits(18),
                price = BigDecimal("4"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"))

        apiClient.tryCancelOrder(
            CancelOrderApiRequest(
                orderId = OrderId.generate(),
                marketId = usdcDaiMarketId,
                amount = BigInteger.ZERO,
                side = OrderSide.Buy,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signCancelOrder(it)
            },
        ).assertError(ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"))

        // invalid signature (malformed signature)
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = OrderId.generate(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = BigDecimal("3").toFundamentalUnits(18),
                price = BigDecimal("4"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ),
        ).assertError(ApiError(ReasonCode.SignatureNotValid, "Invalid signature"))

        // try creating a limit order not a multiple of tick size
        apiClient.tryCreateOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2.015"),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        val createLimitOrderResponse2 = apiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = usdcDaiMarketId,
                side = OrderSide.Buy,
                amount = wallet.formatAmount("1", "USDC"),
                price = BigDecimal("2"),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        )

        wsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(createLimitOrderResponse2, msg.order as Order.Limit, false)
            }
        }

        // try updating the price to something not a tick size
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("1", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.015"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.ProcessingError, "Order price is not a multiple of tick size"),
        )

        // try updating and cancelling an order not created by this wallet - signature should fail
        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)
        apiClient2.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("1", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.01"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )
        apiClient2.tryCancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                marketId = createLimitOrderResponse2.order.marketId,
                amount = createLimitOrderResponse2.order.amount,
                side = createLimitOrderResponse2.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet2.signCancelOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Order not created by this wallet"),
        )

        // invalid signature
        apiClient.tryCancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                marketId = createLimitOrderResponse2.order.marketId,
                amount = createLimitOrderResponse2.order.amount,
                side = createLimitOrderResponse2.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet2.signCancelOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )

        // try update cancelled order
        apiClient.cancelOrder(
            CancelOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                marketId = createLimitOrderResponse2.order.marketId,
                amount = createLimitOrderResponse2.order.amount,
                side = createLimitOrderResponse2.order.side,
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signCancelOrder(it)
            },
        )
        wsClient.apply {
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(createLimitOrderResponse2.orderId, msg.order.id)
                assertEquals(OrderStatus.Cancelled, msg.order.status)
            }
        }
        apiClient.tryUpdateOrder(
            apiRequest = UpdateOrderApiRequest(
                orderId = createLimitOrderResponse2.orderId,
                amount = wallet.formatAmount("3", "USDC"),
                marketId = createLimitOrderResponse2.order.marketId,
                side = createLimitOrderResponse2.order.side,
                price = BigDecimal("2.01"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                wallet.signOrder(it)
            },
        ).assertError(
            ApiError(ReasonCode.RejectedBySequencer, "Order does not exist or is already finalized"),
        )
    }

    @Test
    fun `list and cancel all open orders`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToOrders()
        val initialOrdersOverWs = wsClient.assertOrdersMessageReceived().orders

        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        wsClient.subscribeToLimits(usdcDaiMarketId)
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        Faucet.fund(wallet.address)
        val amountToDeposit = wallet.formatAmount("30", "DAI")
        wallet.mintERC20("DAI", amountToDeposit)
        deposit(wallet, "DAI", amountToDeposit)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance("DAI", amountToDeposit, amountToDeposit)))
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        val limitOrderApiRequest = CreateOrderApiRequest.Limit(
            nonce = generateOrderNonce(),
            marketId = usdcDaiMarketId,
            side = OrderSide.Buy,
            amount = wallet.formatAmount("1", "USDC"),
            price = BigDecimal("2"),
            signature = EvmSignature.emptySignature(),
        ).let {
            wallet.signOrder(it)
        }
        repeat(times = 10) {
            apiClient.createOrder(wallet.signOrder(limitOrderApiRequest.copy(nonce = generateOrderNonce())))
        }
        repeat(10) {
            wsClient.assertOrderCreatedMessageReceived()
            wsClient.assertLimitsMessageReceived(usdcDaiMarketId)
        }
        assertEquals(10, apiClient.listOrders().orders.count { it.status != OrderStatus.Cancelled })

        apiClient.cancelOpenOrders()

        wsClient.assertOrdersMessageReceived { msg ->
            assertNotEquals(initialOrdersOverWs, msg.orders)
            assertTrue(msg.orders.all { it.status == OrderStatus.Cancelled })
        }
        wsClient.assertLimitsMessageReceived(usdcDaiMarketId)

        assertTrue(apiClient.listOrders().orders.all { it.status == OrderStatus.Cancelled })

        wsClient.close()
    }

    @Test
    fun `order execution`() {
        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader(btcEthMarketId, "0.5", null, "ETH", "2")

        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader(btcEthMarketId, "0.5", "0.2", "ETH", "2")

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingETHBalance = makerWallet.getExchangeERC20Balance("ETH")
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingETHBalance = takerWallet.getExchangeERC20Balance("ETH")

        // place an order and see that it gets accepted
        val limitBuyOrderApiResponse = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Buy,
                amount = makerWallet.formatAmount("0.00013345", "BTC"),
                price = BigDecimal("17.45"),
                signature = EvmSignature.emptySignature(),
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<CreateOrderApiRequest.Limit>(limitBuyOrderApiResponse.order)
        makerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(limitBuyOrderApiResponse, msg.order as Order.Limit, false)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("200000000000000000"), msg.base)
                assertEquals(BigInteger("1997671297500000000"), msg.quote)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.450", size = "0.00013345".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        val updatedLimitBuyOrderApiResponse = makerApiClient.updateOrder(
            UpdateOrderApiRequest(
                orderId = limitBuyOrderApiResponse.orderId,
                amount = makerWallet.formatAmount("0.00012345", "BTC"),
                marketId = limitBuyOrderApiResponse.order.marketId,
                side = limitBuyOrderApiResponse.order.side,
                price = BigDecimal("17.50"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<UpdateOrderApiRequest>(updatedLimitBuyOrderApiResponse.order)
        assertEquals(RequestStatus.Accepted, updatedLimitBuyOrderApiResponse.requestStatus)

        makerWsClient.assertOrderUpdatedMessageReceived { msg ->
            validateLimitOrders(updatedLimitBuyOrderApiResponse.order, msg.order as Order.Limit, true)
        }
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = emptyList(),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }
        makerWsClient.assertLimitsMessageReceived(btcEthMarketId) { msg ->
            assertEquals(BigInteger("200000000000000000"), msg.base)
            assertEquals(BigInteger("1997839625000000000"), msg.quote)
        }

        // place a sell order
        val limitSellOrderApiResponse = makerApiClient.createOrder(
            CreateOrderApiRequest.Limit(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Sell,
                amount = makerWallet.formatAmount("0.00154321", "BTC"),
                price = BigDecimal("17.600"),
                signature = EvmSignature.emptySignature(),
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<CreateOrderApiRequest.Limit>(limitSellOrderApiResponse.order)

        makerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                validateLimitOrders(limitSellOrderApiResponse, msg.order as Order.Limit, false)
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("198456790000000000"), msg.base)
                assertEquals(BigInteger("1997839625000000000"), msg.quote)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBookEntry(price = "17.600", size = "0.00154321".toBigDecimal()),
                    ),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }

        // update amount and price of the sell
        val updatedLimitSellOrderApiResponse = makerApiClient.updateOrder(
            UpdateOrderApiRequest(
                orderId = limitSellOrderApiResponse.orderId,
                amount = makerWallet.formatAmount("0.00054321", "BTC"),
                marketId = limitSellOrderApiResponse.order.marketId,
                side = limitSellOrderApiResponse.order.side,
                price = BigDecimal("17.550"),
                nonce = generateOrderNonce(),
                signature = EvmSignature.emptySignature(),
            ).let {
                makerWallet.signOrder(it)
            },
        )
        assertIs<UpdateOrderApiRequest>(updatedLimitSellOrderApiResponse.order)
        assertEquals(RequestStatus.Accepted, updatedLimitSellOrderApiResponse.requestStatus)

        makerWsClient.assertOrderUpdatedMessageReceived { msg ->
            assertIs<Order.Limit>(msg.order)
            validateLimitOrders(updatedLimitSellOrderApiResponse.order, msg.order as Order.Limit, true)
        }
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBookEntry(price = "17.550", size = "0.00054321".toBigDecimal()),
                    ),
                    last = LastTrade("0.000", LastTradeDirection.Unchanged),
                ),
            )
        }
        makerWsClient.assertLimitsMessageReceived(btcEthMarketId) { msg ->
            assertEquals(BigInteger("199456790000000000"), msg.base)
            assertEquals(BigInteger("1997839625000000000"), msg.quote)
        }

        // place a buy order and see it gets executed
        val marketBuyOrderApiResponse = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Buy,
                amount = takerWallet.formatAmount("0.00043210", "BTC"),
                signature = EvmSignature.emptySignature(),
            ).let {
                takerWallet.signOrder(it)
            },
        )

        assertIs<CreateOrderApiRequest.Market>(marketBuyOrderApiResponse.order)

        takerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                assertEquals(marketBuyOrderApiResponse.orderId, msg.order.id)
                assertEquals(marketBuyOrderApiResponse.order.amount, msg.order.amount)
                assertEquals(marketBuyOrderApiResponse.order.side, msg.order.side)
                assertEquals(marketBuyOrderApiResponse.order.marketId, msg.order.marketId)
                assertNotNull(msg.order.timing.createdAt)
            }
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(marketBuyOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(marketBuyOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, (updatedLimitSellOrderApiResponse.order).price.compareTo(msg.trade.price))
                assertEquals(marketBuyOrderApiResponse.order.amount, msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00043210", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(msg.order.executions[0].role, ExecutionRole.Taker)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("1992416645000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("432100000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(updatedLimitSellOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(updatedLimitSellOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(updatedLimitSellOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, (updatedLimitSellOrderApiResponse.order).price.compareTo(msg.trade.price))
                assertEquals(takerWallet.formatAmount("0.00043210", "BTC"), msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, makerWallet.formatAmount("0.00043210", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.550")))
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("2007583355000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("199567900000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = listOf(
                        OrderBookEntry(price = "17.500", size = "0.00012345".toBigDecimal()),
                    ),
                    sell = listOf(
                        OrderBookEntry(price = "17.550", size = "0.00011111".toBigDecimal()),
                    ),
                    last = LastTrade("17.550", LastTradeDirection.Up),
                ),
            )
        }
        val trade = getTradesForOrders(listOf(marketBuyOrderApiResponse.orderId)).first()

        takerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(trade.timestamp),
                        open = 17.55,
                        high = 17.55,
                        low = 17.55,
                        close = 17.55,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("432100000000000"), msg.base)
                assertEquals(BigInteger("1992416645000000000"), msg.quote)
            }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(trade.timestamp),
                        open = 17.55,
                        high = 17.55,
                        low = 17.55,
                        close = 17.55,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("199456790000000000"), msg.base)
                assertEquals(BigInteger("2005422980000000000"), msg.quote)
            }
        }

        assertEquals(trade.amount, takerWallet.formatAmount("0.00043210", "BTC"))
        assertEquals(0, trade.price.compareTo(BigDecimal("17.550")))

        waitForSettlementToFinish(listOf(trade.id.value))

        val baseQuantity = takerWallet.formatAmount("0.00043210", "BTC")
        val notional = (trade.price * trade.amount.fromFundamentalUnits(takerWallet.decimals("BTC"))).toFundamentalUnits(takerWallet.decimals("ETH"))
        assertBalances(
            listOf(
                ExpectedBalance("BTC", makerStartingBTCBalance - baseQuantity, makerStartingBTCBalance - baseQuantity),
                ExpectedBalance("ETH", makerStartingETHBalance + notional, makerStartingETHBalance + notional),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance("BTC", takerStartingBTCBalance + baseQuantity, takerStartingBTCBalance + baseQuantity),
                ExpectedBalance("ETH", takerStartingETHBalance - notional, takerStartingETHBalance - notional),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.assertBalancesMessageReceived { msg ->
            assertEquals(takerStartingBTCBalance + baseQuantity, msg.balances.first { it.symbol.value == "BTC" }.available)
            assertEquals(takerStartingETHBalance - notional, msg.balances.first { it.symbol.value == "ETH" }.available)
        }
        takerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(marketBuyOrderApiResponse.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        makerWsClient.assertBalancesMessageReceived { msg ->
            assertEquals(makerStartingBTCBalance - baseQuantity, msg.balances.first { it.symbol.value == "BTC" }.available)
            assertEquals(makerStartingETHBalance + notional, msg.balances.first { it.symbol.value == "ETH" }.available)
        }
        makerWsClient.assertTradeUpdatedMessageReceived { msg ->
            assertEquals(updatedLimitSellOrderApiResponse.order.orderId, msg.trade.orderId)
            assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
        }

        // place a sell order and see it gets executed
        val marketSellOrderApiResponse = takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = btcEthMarketId,
                side = OrderSide.Sell,
                amount = takerWallet.formatAmount("0.00012346", "BTC"),
                signature = EvmSignature.emptySignature(),
            ).let {
                takerWallet.signOrder(it)
            },
        )

        assertIs<CreateOrderApiRequest.Market>(marketSellOrderApiResponse.order)

        takerWsClient.apply {
            assertOrderCreatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.order.id)
                assertEquals(marketSellOrderApiResponse.order.amount, msg.order.amount)
                assertEquals(marketSellOrderApiResponse.order.side, msg.order.side)
                assertEquals(marketSellOrderApiResponse.order.marketId, msg.order.marketId)
                assertNotNull(msg.order.timing.createdAt)
            }
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(marketSellOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(marketSellOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, msg.trade.price.compareTo(BigDecimal("17.500")))
                assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Partial, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00012345", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.500")))
                assertEquals(ExecutionRole.Taker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("1994577020000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("308650000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        makerWsClient.apply {
            assertTradeCreatedMessageReceived { msg ->
                assertEquals(updatedLimitBuyOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(updatedLimitBuyOrderApiResponse.order.marketId, msg.trade.marketId)
                assertEquals(updatedLimitBuyOrderApiResponse.order.side, msg.trade.side)
                assertEquals(0, updatedLimitBuyOrderApiResponse.order.price.compareTo(msg.trade.price))
                assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), msg.trade.amount)
                assertEquals(SettlementStatus.Pending, msg.trade.settlementStatus)
            }
            assertOrderUpdatedMessageReceived { msg ->
                assertEquals(OrderStatus.Filled, msg.order.status)
                assertEquals(1, msg.order.executions.size)
                assertEquals(msg.order.executions[0].amount, takerWallet.formatAmount("0.00012345", "BTC"))
                assertEquals(0, msg.order.executions[0].price.compareTo(BigDecimal("17.500")))
                assertEquals(ExecutionRole.Maker, msg.order.executions[0].role)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(BigInteger("2005422980000000000"), msg.balances.first { it.symbol.value == "ETH" }.available)
                assertEquals(BigInteger("199691350000000000"), msg.balances.first { it.symbol.value == "BTC" }.available)
            }
        }

        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = emptyList(),
                    sell = listOf(
                        OrderBookEntry(price = "17.550", size = "0.00011111".toBigDecimal()),
                    ),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }

        val trade2 = getTradesForOrders(listOf(marketSellOrderApiResponse.orderId)).first()
        assertEquals(trade2.amount, takerWallet.formatAmount("0.00012345", "BTC"))
        assertEquals(0, trade2.price.compareTo(BigDecimal("17.500")))

        waitForSettlementToFinish(listOf(trade2.id.value))

        val baseQuantity2 = takerWallet.formatAmount("0.00012345", "BTC")
        val notional2 = (trade2.price * trade2.amount.fromFundamentalUnits(takerWallet.decimals("BTC"))).toFundamentalUnits(takerWallet.decimals("ETH"))
        assertBalances(
            listOf(
                ExpectedBalance("BTC", makerStartingBTCBalance - baseQuantity + baseQuantity2, makerStartingBTCBalance - baseQuantity + baseQuantity2),
                ExpectedBalance("ETH", makerStartingETHBalance + notional - notional2, makerStartingETHBalance + notional - notional2),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance("BTC", takerStartingBTCBalance + baseQuantity - baseQuantity2, takerStartingBTCBalance + baseQuantity - baseQuantity2),
                ExpectedBalance("ETH", takerStartingETHBalance - notional + notional2, takerStartingETHBalance - notional + notional2),
            ),
            takerApiClient.getBalances().balances,
        )

        takerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(trade.timestamp),
                        open = 17.55,
                        high = 17.55,
                        low = 17.5,
                        close = 17.5,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("308650000000000"), msg.base)
                assertEquals(BigInteger("1994577020000000000"), msg.quote)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(takerStartingBTCBalance + baseQuantity - baseQuantity2, msg.balances.first { it.symbol.value == "BTC" }.available)
                assertEquals(takerStartingETHBalance - notional + notional2, msg.balances.first { it.symbol.value == "ETH" }.available)
            }
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(marketSellOrderApiResponse.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

        makerWsClient.apply {
            assertPricesMessageReceived(btcEthMarketId) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(trade.timestamp),
                        open = 17.55,
                        high = 17.55,
                        low = 17.5,
                        close = 17.5,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("199580240000000000"), msg.base)
                assertEquals(BigInteger("2005422980000000000"), msg.quote)
            }
            assertBalancesMessageReceived { msg ->
                assertEquals(makerStartingBTCBalance - baseQuantity + baseQuantity2, msg.balances.first { it.symbol.value == "BTC" }.available)
                assertEquals(makerStartingETHBalance + notional - notional2, msg.balances.first { it.symbol.value == "ETH" }.available)
            }
            assertTradeUpdatedMessageReceived { msg ->
                assertEquals(updatedLimitBuyOrderApiResponse.order.orderId, msg.trade.orderId)
                assertEquals(SettlementStatus.Completed, msg.trade.settlementStatus)
            }
        }

        takerApiClient.cancelOpenOrders()
        takerWsClient.assertOrdersMessageReceived()

        makerApiClient.cancelOpenOrders()
        makerWsClient.assertOrdersMessageReceived()
        listOf(makerWsClient, takerWsClient).forEach { wsClient ->
            wsClient.assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = emptyList(),
                    sell = emptyList(),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )
        }
        makerWsClient.assertLimitsMessageReceived(btcEthMarketId)

        // verify that client's websocket gets same state on reconnect
        takerWsClient.close()
        WebsocketClient.blocking(takerApiClient.authToken).apply {
            subscribeToOrders()
            assertOrdersMessageReceived { msg ->
                assertEquals(2, msg.orders.size)
                msg.orders[0].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketSellOrderApiResponse.orderId, order.id)
                    assertEquals(marketSellOrderApiResponse.order.marketId, order.marketId)
                    assertEquals(marketSellOrderApiResponse.order.side, order.side)
                    assertEquals(marketSellOrderApiResponse.order.amount, order.amount)
                    assertEquals(OrderStatus.Partial, order.status)
                }
                msg.orders[1].also { order ->
                    assertIs<Order.Market>(order)
                    assertEquals(marketBuyOrderApiResponse.orderId, order.id)
                    assertEquals(marketBuyOrderApiResponse.order.marketId, order.marketId)
                    assertEquals(marketBuyOrderApiResponse.order.side, order.side)
                    assertEquals(marketBuyOrderApiResponse.order.amount, order.amount)
                    assertEquals(OrderStatus.Filled, order.status)
                }
            }

            subscribeToTrades()
            assertTradesMessageReceived { msg ->
                assertEquals(2, msg.trades.size)
                msg.trades[0].apply {
                    assertEquals(marketSellOrderApiResponse.orderId, orderId)
                    assertEquals(marketSellOrderApiResponse.order.marketId, marketId)
                    assertEquals(marketSellOrderApiResponse.order.side, side)
                    assertEquals(0, price.compareTo(BigDecimal("17.500")))
                    assertEquals(makerWallet.formatAmount("0.00012345", "BTC"), amount)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
                msg.trades[1].apply {
                    assertEquals(marketBuyOrderApiResponse.orderId, orderId)
                    assertEquals(marketBuyOrderApiResponse.order.marketId, marketId)
                    assertEquals(marketBuyOrderApiResponse.order.side, side)
                    assertEquals(0, updatedLimitSellOrderApiResponse.order.price.compareTo(price))
                    assertEquals(marketBuyOrderApiResponse.order.amount, amount)
                    assertEquals(SettlementStatus.Completed, settlementStatus)
                }
            }

            subscribeToOrderBook(btcEthMarketId)
            assertOrderBookMessageReceived(
                btcEthMarketId,
                OrderBook(
                    marketId = btcEthMarketId,
                    buy = emptyList(),
                    sell = emptyList(),
                    last = LastTrade("17.500", LastTradeDirection.Down),
                ),
            )

            subscribeToLimits(btcEthMarketId)
            assertLimitsMessageReceived(btcEthMarketId) { msg ->
                assertEquals(BigInteger("308650000000000"), msg.base)
                assertEquals(BigInteger("1994577020000000000"), msg.quote)
            }
        }.close()

        makerWsClient.close()
    }

    @Test
    fun `order batches`() {
        val (makerApiClient, makerWallet, makerWsClient) =
            setupTrader(btcUsdcMarketId, "0.5", "0.2", "USDC", "500", subscribeToOrderBook = false)

        val (takerApiClient, takerWallet, takerWsClient) =
            setupTrader(btcUsdcMarketId, "0.5", null, "USDC", "500", subscribeToOrderBook = false)

        // starting onchain balances
        val makerStartingBTCBalance = makerWallet.getExchangeNativeBalance()
        val makerStartingUSDCBalance = makerWallet.getExchangeERC20Balance("USDC")
        val takerStartingBTCBalance = takerWallet.getExchangeNativeBalance()
        val takerStartingUSDCBalance = takerWallet.getExchangeERC20Balance("USDC")

        // place 3 orders
        val createBatchLimitOrders = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = btcUsdcMarketId,
                createOrders = listOf("0.00001", "0.00002", "0.0003").map {
                    CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = MarketId("BTC/USDC"),
                        side = OrderSide.Sell,
                        amount = makerWallet.formatAmount(it, "BTC"),
                        price = BigDecimal("68400.000"),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signOrder(it)
                    }
                },
                updateOrders = listOf(),
                cancelOrders = listOf(),
            ),
        )

        assertEquals(3, createBatchLimitOrders.createdOrders.count())
        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
            assertEquals(BigInteger("199670000000000000"), msg.base)
            assertEquals(BigInteger("500000000"), msg.quote)
        }

        val batchOrderResponse = makerApiClient.batchOrders(
            BatchOrdersApiRequest(
                marketId = btcUsdcMarketId,
                createOrders = listOf("0.0004", "0.0005", "0.0006").map {
                    CreateOrderApiRequest.Limit(
                        nonce = generateOrderNonce(),
                        marketId = MarketId("BTC/USDC"),
                        side = OrderSide.Sell,
                        amount = makerWallet.formatAmount(it, "BTC"),
                        price = BigDecimal("68400.000"),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signOrder(it)
                    }
                },
                updateOrders = listOf(
                    UpdateOrderApiRequest(
                        orderId = createBatchLimitOrders.createdOrders[0].orderId,
                        amount = makerWallet.formatAmount("0.0001", "BTC"),
                        marketId = createBatchLimitOrders.createdOrders[0].order.marketId,
                        side = createBatchLimitOrders.createdOrders[0].order.side,
                        price = BigDecimal("68405.000"),
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signOrder(it)
                    },
                    UpdateOrderApiRequest(
                        orderId = createBatchLimitOrders.createdOrders[1].orderId,
                        amount = makerWallet.formatAmount("0.0002", "BTC"),
                        marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        price = BigDecimal("68405.000"),
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signOrder(it)
                    },
                    UpdateOrderApiRequest(
                        orderId = OrderId.generate(),
                        amount = makerWallet.formatAmount("0.0002", "BTC"),
                        marketId = createBatchLimitOrders.createdOrders[1].order.marketId,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        price = BigDecimal("68405.000"),
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signOrder(it)
                    },
                ),
                cancelOrders = listOf(
                    CancelOrderApiRequest(
                        orderId = createBatchLimitOrders.createdOrders[2].orderId,
                        marketId = createBatchLimitOrders.createdOrders[2].order.marketId,
                        amount = createBatchLimitOrders.createdOrders[2].order.amount,
                        side = createBatchLimitOrders.createdOrders[2].order.side,
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signCancelOrder(it)
                    },
                    CancelOrderApiRequest(
                        orderId = OrderId.generate(),
                        marketId = MarketId("BTC/USDC"),
                        amount = BigInteger.ZERO,
                        side = OrderSide.Buy,
                        nonce = generateOrderNonce(),
                        signature = EvmSignature.emptySignature(),
                    ).let {
                        makerWallet.signCancelOrder(it)
                    },
                ),
            ),
        )

        assertEquals(3, batchOrderResponse.createdOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(2, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.updatedOrders.count { it.requestStatus == RequestStatus.Rejected })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Accepted })
        assertEquals(1, batchOrderResponse.canceledOrders.count { it.requestStatus == RequestStatus.Rejected })

        repeat(3) { makerWsClient.assertOrderCreatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
            assertEquals(BigInteger("197900000000000000"), msg.base)
            assertEquals(BigInteger("500000000"), msg.quote)
        }
        repeat(3) { makerWsClient.assertOrderUpdatedMessageReceived() }
        makerWsClient.assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
            assertEquals(BigInteger("198200000000000000"), msg.base)
            assertEquals(BigInteger("500000000"), msg.quote)
        }

        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Open })

        // total BTC available is 0.0001 + 0.0002 + 0.0004 + 0.0005 + 0.0006 = 0.0018
        val takerOrderAmount = takerWallet.formatAmount("0.0018", "BTC")
        // create a market orders that should match against the 5 limit orders
        takerApiClient.createOrder(
            CreateOrderApiRequest.Market(
                nonce = generateOrderNonce(),
                marketId = btcUsdcMarketId,
                side = OrderSide.Buy,
                amount = takerOrderAmount,
                signature = EvmSignature.emptySignature(),
            ).let {
                takerWallet.signOrder(it)
            },
        )

        takerWsClient.apply {
            assertOrderCreatedMessageReceived()
            repeat(5) { assertTradeCreatedMessageReceived() }
            assertOrderUpdatedMessageReceived()
            assertBalancesMessageReceived()
            assertPricesMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(
                    OHLC(
                        // initial ohlc in the BTC/USDC market
                        // price is weighted across limit orders that have been filled within execution
                        start = OHLCDuration.P5M.durationStart(Clock.System.now()),
                        open = 68400.3,
                        high = 68400.3,
                        low = 68400.3,
                        close = 68400.3,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(BigInteger("1800000000000000"), msg.base)
                assertEquals(BigInteger("376879400"), msg.quote)
            }
        }

        makerWsClient.apply {
            repeat(5) { assertTradeCreatedMessageReceived() }
            repeat(5) { assertOrderUpdatedMessageReceived() }
            assertBalancesMessageReceived()
            assertPricesMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(
                    OHLC(
                        start = OHLCDuration.P5M.durationStart(Clock.System.now()),
                        open = 68400.3,
                        high = 68400.3,
                        low = 68400.3,
                        close = 68400.3,
                        duration = OHLCDuration.P5M,
                    ),
                    msg.ohlc.last(),
                )
            }
            assertLimitsMessageReceived(btcUsdcMarketId) { msg ->
                assertEquals(BigInteger("198200000000000000"), msg.base)
                assertEquals(BigInteger("623120600"), msg.quote)
            }
        }

        // should be 8 filled orders
        val takerOrders = takerApiClient.listOrders().orders
        assertEquals(1, takerOrders.count { it.status == OrderStatus.Filled })
        assertEquals(5, makerApiClient.listOrders().orders.count { it.status == OrderStatus.Filled })

        // now verify the trades

        val expectedAmounts = listOf("0.0001", "0.0002", "0.0004", "0.0005", "0.0006").map { takerWallet.formatAmount(it, "BTC") }.toSet()
        val trades = getTradesForOrders(takerOrders.map { it.id })
        val prices = trades.map { it.price }.toSet()

        assertEquals(5, trades.size)
        assertEquals(expectedAmounts, trades.map { it.amount }.toSet())
        assertEquals(prices.size, 2)

        waitForSettlementToFinish(trades.map { it.id.value })

        val notional = trades.map {
            (it.price * it.amount.fromFundamentalUnits(makerWallet.decimals("BTC"))).toFundamentalUnits(takerWallet.decimals("USDC"))
        }.reduce { acc, notionalForTrade -> acc + notionalForTrade }

        // val notional = (prices.first() * BigDecimal("0.0018")).toFundamentalUnits(takerWallet.decimals("USDC"))
        assertBalances(
            listOf(
                ExpectedBalance("BTC", makerStartingBTCBalance - takerOrderAmount, makerStartingBTCBalance - takerOrderAmount),
                ExpectedBalance("USDC", makerStartingUSDCBalance + notional, makerStartingUSDCBalance + notional),
            ),
            makerApiClient.getBalances().balances,
        )
        assertBalances(
            listOf(
                ExpectedBalance("BTC", takerStartingBTCBalance + takerOrderAmount, takerStartingBTCBalance + takerOrderAmount),
                ExpectedBalance("USDC", takerStartingUSDCBalance - notional, takerStartingUSDCBalance - notional),
            ),
            takerApiClient.getBalances().balances,
        )

        takerApiClient.cancelOpenOrders()
        makerApiClient.cancelOpenOrders()

        makerWsClient.close()
        takerWsClient.close()
    }

    private fun validateLimitOrders(response: CreateOrderApiResponse, order: Order.Limit, updated: Boolean) {
        assertEquals(response.orderId, order.id)
        assertEquals(response.order.amount, order.amount)
        assertEquals(response.order.side, order.side)
        assertEquals(response.order.marketId, order.marketId)
        assertEquals(0, (response.order as CreateOrderApiRequest.Limit).price.compareTo(order.price))
        assertNotNull(order.timing.createdAt)
        if (updated) {
            assertNotNull(order.timing.updatedAt)
        }
    }

    private fun validateLimitOrders(response: UpdateOrderApiRequest, order: Order.Limit, updated: Boolean) {
        assertEquals(response.orderId, order.id)
        assertEquals(response.amount, order.amount)
        assertEquals(response.side, order.side)
        assertEquals(response.marketId, order.marketId)
        assertEquals(0, response.price.compareTo(order.price))
        assertNotNull(order.timing.createdAt)
        if (updated) {
            assertNotNull(order.timing.updatedAt)
        }
    }

    private fun setupTrader(
        marketId: MarketId,
        nativeAmount: String,
        nativeDepositAmount: String?,
        mintSymbol: String,
        mintAmount: String,
        subscribeToOrderBook: Boolean = true,
        subscribeToOrderPrices: Boolean = true,
    ): Triple<TestApiClient, Wallet, WsClient> {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken).apply {
            if (subscribeToOrderBook) {
                subscribeToOrderBook(marketId)
                assertOrderBookMessageReceived(marketId)
            }

            if (subscribeToOrderPrices) {
                subscribeToPrices(marketId)
                assertPricesMessageReceived(marketId)
            }

            subscribeToOrders()
            assertOrdersMessageReceived()

            subscribeToTrades()
            assertTradesMessageReceived()

            subscribeToBalances()
            assertBalancesMessageReceived()

            subscribeToLimits(marketId)
            assertLimitsMessageReceived(marketId)
        }

        Faucet.fund(wallet.address, wallet.formatAmount(nativeAmount, "BTC"))
        val formattedMintAmount = wallet.formatAmount(mintAmount, mintSymbol)
        wallet.mintERC20(mintSymbol, formattedMintAmount)

        deposit(wallet, mintSymbol, formattedMintAmount)
        waitForBalance(apiClient, wsClient, listOf(ExpectedBalance(mintSymbol, formattedMintAmount, formattedMintAmount)))
        wsClient.assertLimitsMessageReceived(marketId)

        nativeDepositAmount?.also {
            val formattedNativeAmount = wallet.formatAmount(it, "BTC")
            deposit(wallet, "BTC", formattedNativeAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(mintSymbol, formattedMintAmount, formattedMintAmount),
                    ExpectedBalance("BTC", formattedNativeAmount, formattedNativeAmount),
                ),
            )
            wsClient.assertLimitsMessageReceived(marketId)
        }

        return Triple(apiClient, wallet, wsClient)
    }

    private fun deposit(wallet: Wallet, asset: String, amount: BigInteger) {
        // deposit onchain and update sequencer
        if (asset == "BTC") {
            wallet.depositNative(amount)
        } else {
            wallet.depositERC20(asset, amount)
        }
    }

    private fun waitForSettlementToFinish(tradeIds: List<TradeId>) {
        await
            .withAlias("Waiting for trade settlement to finish. TradeIds: ${tradeIds.joinToString { it.value }}")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(20000L))
            .until {
                Faucet.mine()
                transaction {
                    TradeEntity.count(TradeTable.guid.inList(tradeIds) and TradeTable.settlementStatus.eq(SettlementStatus.Completed))
                } == tradeIds.size.toLong()
            }
    }

    private fun getTradesForOrders(orderIds: List<OrderId>): List<TradeEntity> {
        return transaction {
            OrderExecutionEntity.findForOrders(orderIds).map { it.trade }
        }
    }

    private fun validateNonceAndSignatureStored(orderId: OrderId, nonce: String, signature: EvmSignature) {
        transaction {
            val orderEntity = OrderEntity[orderId]
            assertEquals(BigInteger(orderEntity.nonce, 16), BigInteger(nonce, 16))
            assertEquals(orderEntity.signature, signature.value)
        }
    }
}
