package co.chainring.core.client.ws

import co.chainring.apps.api.model.websocket.IncomingWSMessage
import co.chainring.apps.api.model.websocket.OutgoingWSMessage
import co.chainring.apps.api.model.websocket.SubscriptionTopic
import co.chainring.core.client.rest.apiServerRootUrl
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCDuration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.http4k.client.WebsocketClient
import org.http4k.core.Uri
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsClient
import org.http4k.websocket.WsConsumer
import org.http4k.websocket.WsMessage
import kotlin.time.Duration
import kotlin.time.toJavaDuration

fun WebsocketClient.blocking(auth: String?): WsClient =
    blocking(
        uri = Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect" + (auth?.let { "?auth=$auth" } ?: "")),
    )

fun WsClient.send(message: IncomingWSMessage) {
    send(WsMessage(Json.encodeToString(message)))
}

fun WsClient.subscribeToOrderBook(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.OrderBook(marketId)))
}

fun WsClient.subscribeToPrices(marketId: MarketId, duration: OHLCDuration = OHLCDuration.P5M) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Prices(marketId, duration)))
}

fun WsClient.subscribeToOrders() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Orders))
}

fun WsClient.subscribeToTrades() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Trades))
}

fun WsClient.subscribeToBalances() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Balances))
}

fun WsClient.subscribeToLimits(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Limits(marketId)))
}

fun WsClient.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}

fun WsClient.receivedDecoded(): Sequence<OutgoingWSMessage> =
    received().map {
        Json.decodeFromString<OutgoingWSMessage>(it.bodyString())
    }

// non blocking client/methods
fun WebsocketClient.nonBlocking(auth: String?, timeout: Duration = Duration.ZERO, onConnect: WsConsumer): Websocket =
    nonBlocking(
        uri = Uri.of(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect" + (auth?.let { "?auth=$auth" } ?: "")),
        timeout = timeout.toJavaDuration(),
        onConnect = onConnect,
    )

fun Websocket.send(message: IncomingWSMessage) {
    send(WsMessage(Json.encodeToString(message)))
}

fun WebSocket.send(message: IncomingWSMessage) {
    send(Json.encodeToString(message))
}

fun Websocket.subscribeToOrderBook(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.OrderBook(marketId)))
}

fun Websocket.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
}

fun WebSocket.subscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Subscribe(topic))
}

fun Websocket.subscribeToPrices(marketId: MarketId, duration: OHLCDuration = OHLCDuration.P5M) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Prices(marketId, duration)))
}

fun Websocket.subscribeToOrders() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Orders))
}

fun Websocket.subscribeToTrades() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Trades))
}

fun Websocket.subscribeToBalances() {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Balances))
}

fun Websocket.subscribeToLimits(marketId: MarketId) {
    send(IncomingWSMessage.Subscribe(SubscriptionTopic.Limits(marketId)))
}

fun Websocket.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}

fun WebSocket.unsubscribe(topic: SubscriptionTopic) {
    send(IncomingWSMessage.Unsubscribe(topic))
}
