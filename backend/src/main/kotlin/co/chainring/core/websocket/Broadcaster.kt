package co.chainring.core.websocket

import co.chainring.apps.api.model.BigDecimalJson
import co.chainring.apps.api.model.websocket.Balances
import co.chainring.apps.api.model.websocket.LastTrade
import co.chainring.apps.api.model.websocket.LastTradeDirection
import co.chainring.apps.api.model.websocket.OHLC
import co.chainring.apps.api.model.websocket.OrderBook
import co.chainring.apps.api.model.websocket.OrderBookEntry
import co.chainring.apps.api.model.websocket.OrderCreated
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.Prices
import co.chainring.apps.api.model.websocket.Publishable
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.apps.api.model.websocket.TradeCreated
import co.chainring.apps.api.model.websocket.TradeUpdated
import co.chainring.apps.api.model.websocket.Trades
import co.chainring.apps.api.wsUnauthorized
import co.chainring.core.model.Address
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BroadcasterJobEntity
import co.chainring.core.model.db.BroadcasterJobId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.utils.PgListener
import co.chainring.core.utils.Timer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsMessage
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

typealias Principal = Address

data class ConnectedClient(
    val websocket: Websocket,
    val principal: Principal?,
    val validUntil: Instant,
) : Websocket by websocket {
    fun send(message: OutgoingWSMessage) {
        if (validUntil < Clock.System.now()) {
            websocket.close(wsUnauthorized)
        }
        websocket.send(WsMessage(Json.encodeToString(message)))
    }
}

typealias Subscriptions = CopyOnWriteArrayList<ConnectedClient>
typealias TopicSubscriptions = ConcurrentHashMap<SubscriptionTopic, Subscriptions>

class Broadcaster(val db: Database) {
    private val subscriptions = TopicSubscriptions()
    private val subscriptionsByPrincipal = ConcurrentHashMap<Principal, TopicSubscriptions>()
    private val lastPricePublish = mutableMapOf<Pair<MarketId, ConnectedClient>, Instant>()
    private val rnd = Random(0)

    private val pgListener = PgListener(db, "broadcaster-listener", "broadcaster_ctl") { notification ->
        handleDbNotification(notification.parameter)
    }

    fun subscribe(topic: SubscriptionTopic, client: ConnectedClient) {
        subscriptions.getOrPut(topic) {
            Subscriptions()
        }.addIfAbsent(client)

        client.principal?.also { principal ->
            subscriptionsByPrincipal.getOrPut(principal) {
                TopicSubscriptions()
            }.getOrPut(topic) {
                Subscriptions()
            }.addIfAbsent(client)
        }

        when (topic) {
            is SubscriptionTopic.OrderBook -> sendOrderBook(topic, client)
            is SubscriptionTopic.Prices -> sendPrices(topic, client)
            is SubscriptionTopic.Trades -> sendTrades(client)
            is SubscriptionTopic.Orders -> sendOrders(client)
            is SubscriptionTopic.Balances -> sendBalances(client)
        }
    }

    fun unsubscribe(topic: SubscriptionTopic, client: ConnectedClient) {
        subscriptions[topic]?.remove(client)
        if (topic is SubscriptionTopic.Prices) {
            lastPricePublish.remove(Pair(topic.marketId, client))
        }
        client.principal?.also { principal ->
            subscriptionsByPrincipal[principal]?.get(topic)?.remove(client)
        }
    }

    fun unsubscribe(client: ConnectedClient) {
        subscriptions.keys.forEach { topic ->
            unsubscribe(topic, client)
        }
        client.principal?.also { principal ->
            subscriptionsByPrincipal.remove(principal)
        }
    }

    private var timer: Timer? = null

    fun start() {
        timer = Timer(logger)
        timer?.scheduleAtFixedRate(Duration.ofSeconds(1), stopOnError = true, this::publishData)
        pgListener.start()
    }

    fun stop() {
        timer?.cancel()
        pgListener.stop()
    }

    private fun publishData() {
        subscriptions.forEach { (topic, clients) ->
            when (topic) {
                is SubscriptionTopic.OrderBook -> sendOrderBook(topic, clients)
                is SubscriptionTopic.Prices -> sendPrices(topic, clients)
                else -> {}
            }
        }
    }

    private val mockPrices = ConcurrentHashMap(
        mapOf(
            MarketId("BTC/ETH") to 17.2,
            MarketId("USDC/DAI") to 1.05,
        ),
    )

    private fun mockOHLC(
        marketId: MarketId,
        startTime: Instant,
        duration: kotlin.time.Duration,
        count: Long,
        full: Boolean,
    ): List<OHLC> {
        fun priceAdjust(range: Double, direction: Int) =
            1 + (rnd.nextDouble() * range) + when (direction) {
                0 -> -(range / 2)
                -1 -> -(2 * range)
                else -> 0.0
            }
        return (0 until count).map { i ->
            val curPrice = mockPrices[marketId]!!
            val nextPrice = curPrice * priceAdjust(if (full) 0.001 else 0.0001, 0)
            mockPrices[marketId] = nextPrice
            OHLC(
                start = startTime.plus((duration.inWholeSeconds * i).seconds),
                open = curPrice,
                high = max(curPrice, nextPrice) * priceAdjust(0.0001, 1),
                low = min(curPrice, nextPrice) * priceAdjust(0.0001, -1),
                close = nextPrice,
                durationMs = duration.inWholeMilliseconds,
            )
        }
    }

    private fun sendPrices(topic: SubscriptionTopic.Prices, clients: List<ConnectedClient>) {
        clients.forEach { sendPrices(topic, it) }
    }

    private fun sendPrices(topic: SubscriptionTopic.Prices, client: ConnectedClient) {
        val key = Pair(topic.marketId, client)
        val fullDump = !lastPricePublish.containsKey(key)
        val now = Clock.System.now()
        val prices = if (fullDump) {
            lastPricePublish[key] = now
            Prices(
                market = topic.marketId,
                ohlc = mockOHLC(topic.marketId, now.minus(7.days), 5.minutes, 12 * 24 * 7, true),
                full = true,
            )
        } else {
            Prices(
                market = topic.marketId,
                ohlc = mockOHLC(
                    topic.marketId,
                    lastPricePublish[key]!!,
                    1.seconds,
                    (now - lastPricePublish[key]!!).inWholeSeconds,
                    false,
                ),
                full = false,
            ).also {
                lastPricePublish[key] = now
            }
        }
        client.send(OutgoingWSMessage.Publish(topic, prices))
    }

    private fun sendTrades(client: ConnectedClient) {
        if (client.principal != null) {
            transaction {
                client.send(
                    OutgoingWSMessage.Publish(
                        SubscriptionTopic.Trades,
                        Trades(
                            OrderExecutionEntity
                                .listForWallet(
                                    WalletEntity.getOrCreate(client.principal),
                                    beforeTimestamp = Clock.System.now(),
                                    limit = 1000,
                                ).map(OrderExecutionEntity::toTradeResponse),
                        ),
                    ),
                )
            }
        }
    }

    private fun sendOrderBook(topic: SubscriptionTopic.OrderBook, clients: List<ConnectedClient>) {
        clients.forEach { sendOrderBook(topic, it) }
    }

    private fun sendOrderBook(topic: SubscriptionTopic.OrderBook, client: ConnectedClient) {
        when (topic.marketId.value) {
            "BTC/ETH" -> {
                fun stutter() = rnd.nextDouble(-0.5, 0.5)
                val lastStutter = stutter() / 3.0

                OrderBook(
                    marketId = topic.marketId,
                    last = LastTrade(
                        String.format("%.2f", (17.5 + lastStutter)),
                        if (lastStutter > 0) LastTradeDirection.Up else LastTradeDirection.Down,
                    ),
                    buy = listOf(
                        OrderBookEntry("17.75", BigDecimalJson(2.3 + stutter())),
                        OrderBookEntry("18.00", BigDecimalJson(2.8 + stutter())),
                        OrderBookEntry("18.25", BigDecimalJson(5 + stutter())),
                        OrderBookEntry("18.50", BigDecimalJson(10.1 + stutter())),
                        OrderBookEntry("18.75", BigDecimalJson(9.5 + stutter())),
                        OrderBookEntry("19.00", BigDecimalJson(12.4 + stutter())),
                        OrderBookEntry("19.50", BigDecimalJson(14.2 + stutter())),
                        OrderBookEntry("20.00", BigDecimalJson(15.3 + stutter())),
                        OrderBookEntry("20.50", BigDecimalJson(19 + stutter())),
                    ),
                    sell = listOf(
                        OrderBookEntry("17.25", BigDecimalJson(2.1 + stutter())),
                        OrderBookEntry("17.00", BigDecimalJson(5 + stutter())),
                        OrderBookEntry("16.75", BigDecimalJson(5.4 + stutter())),
                        OrderBookEntry("16.50", BigDecimalJson(7.5 + stutter())),
                        OrderBookEntry("16.25", BigDecimalJson(10.1 + stutter())),
                        OrderBookEntry("16.00", BigDecimalJson(7.5 + stutter())),
                        OrderBookEntry("15.50", BigDecimalJson(12.4 + stutter())),
                        OrderBookEntry("15.00", BigDecimalJson(11.3 + stutter())),
                        OrderBookEntry("14.50", BigDecimalJson(14 + stutter())),
                        OrderBookEntry("14.00", BigDecimalJson(19.5 + stutter())),
                    ),
                )
            }
            "USDC/DAI" -> {
                fun stutter() = rnd.nextDouble(-0.01, 0.01)
                val lastStutter = stutter()

                OrderBook(
                    marketId = topic.marketId,
                    last = LastTrade(
                        String.format("%.2f", (1.03 + lastStutter)),
                        if (lastStutter > 0) LastTradeDirection.Up else LastTradeDirection.Down,
                    ),
                    buy = listOf(
                        OrderBookEntry("1.05", BigDecimalJson(100 + stutter())),
                        OrderBookEntry("1.06", BigDecimalJson(200 + stutter())),
                        OrderBookEntry("1.07", BigDecimalJson(10 + stutter())),
                        OrderBookEntry("1.08", BigDecimalJson(150 + stutter())),
                        OrderBookEntry("1.09", BigDecimalJson(20 + stutter())),
                    ),
                    sell = listOf(
                        OrderBookEntry("1.04", BigDecimalJson(120 + stutter())),
                        OrderBookEntry("1.03", BigDecimalJson(300 + stutter())),
                        OrderBookEntry("1.02", BigDecimalJson(100 + stutter())),
                        OrderBookEntry("1.01", BigDecimalJson(50 + stutter())),
                        OrderBookEntry("1.00", BigDecimalJson(30 + stutter())),
                    ),
                )
            }
            else -> null
        }?.also { orderBook ->
            client.send(OutgoingWSMessage.Publish(topic, orderBook))
        }
    }

    private fun sendOrders(client: ConnectedClient) {
        if (client.principal != null) {
            transaction {
                client.send(
                    OutgoingWSMessage.Publish(
                        SubscriptionTopic.Orders,
                        Orders(
                            OrderEntity
                                .listForWallet(WalletEntity.getOrCreate(client.principal))
                                .map(OrderEntity::toOrderResponse),
                        ),
                    ),
                )
            }
        }
    }

    private fun sendOrders(principal: Principal) {
        findClients(principal, SubscriptionTopic.Orders)
            .forEach { sendOrders(it) }
    }

    private fun sendBalances(client: ConnectedClient) {
        if (client.principal != null) {
            transaction {
                client.send(
                    OutgoingWSMessage.Publish(
                        SubscriptionTopic.Balances,
                        Balances(
                            BalanceEntity.balancesAsApiResponse(WalletEntity.getOrCreate(client.principal)).balances,
                        ),
                    ),
                )
            }
        }
    }

    private fun sendBalances(principal: Principal) {
        findClients(principal, SubscriptionTopic.Balances)
            .forEach { sendBalances(it) }
    }

    private fun handleDbNotification(payload: String) {
        logger.debug { "received db notification with payload $payload" }
        try {
            val notificationData = transaction {
                BroadcasterJobEntity.findById(BroadcasterJobId(payload))?.notificationData
            }
            notificationData?.forEach { principalNotifications ->
                principalNotifications.notifications.forEach { notification ->
                    notify(principalNotifications.principal, notification)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Broadcaster: Unhandled exception " }
        }
    }

    private fun notify(principal: Principal, message: Publishable) {
        val topic = when (message) {
            is OrderBook -> SubscriptionTopic.OrderBook(message.marketId)
            is Orders, is OrderCreated, is OrderUpdated -> SubscriptionTopic.Orders
            is Prices -> SubscriptionTopic.Prices(message.market)
            is Trades, is TradeCreated, is TradeUpdated -> SubscriptionTopic.Trades
            is Balances -> SubscriptionTopic.Balances
        }

        findClients(principal, topic)
            .forEach {
                try {
                    it.send(OutgoingWSMessage.Publish(topic, message))
                } catch (e: Exception) {
                    logger.warn(e) { "error sending message $principal $topic $message " }
                }
            }
    }

    private fun findClients(principal: Principal, topic: SubscriptionTopic): List<ConnectedClient> =
        subscriptionsByPrincipal[principal]?.get(topic) ?: emptyList()
}
