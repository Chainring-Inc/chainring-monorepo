package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.Trade
import co.chainring.apps.api.model.TradesApiResponse
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.SettlementStatus
import co.chainring.core.model.db.TradeId
import co.chainring.core.model.db.WalletEntity
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Query
import org.http4k.lens.string
import org.jetbrains.exposed.sql.transactions.transaction

object TradeRoutes {
    val listTrades: ContractRoute = run {
        val responseBody = Body.auto<TradesApiResponse>().toLens()

        "trades" meta {
            operationId = "list-trades"
            summary = "List trades"
            security = signedTokenSecurity
            tags += listOf(Tag("trade"))
            queries += Query.string().optional("before-timestamp", "Return trades executed before provided timestamp")
            queries += Query.string().optional("limit", "Number of trades to return")
            returning(
                Status.OK,
                responseBody to TradesApiResponse(
                    listOf(
                        Trade(
                            TradeId("trade_1234"),
                            Clock.System.now(),
                            OrderId("1234"),
                            MarketId("BTC/ETH"),
                            ExecutionRole.Taker,
                            counterOrderId = OrderId("4321"),
                            OrderSide.Buy,
                            12345.toBigInteger(),
                            17.61.toBigDecimal(),
                            500.toBigInteger(),
                            Symbol("ETH"),
                            SettlementStatus.Pending,
                        ),
                    ),
                ),
            )
        } bindContract Method.GET to { request ->
            val timestamp = request.query("before-timestamp")?.toInstant() ?: Instant.DISTANT_FUTURE
            val limit = request.query("limit")?.toInt() ?: 100

            val trades = transaction {
                OrderExecutionEntity.listForWallet(
                    wallet = WalletEntity.getOrCreate(request.principal),
                    beforeTimestamp = timestamp,
                    limit = limit,
                ).map { it.toTradeResponse() }
            }

            Response(Status.OK).with(
                responseBody of TradesApiResponse(trades = trades),
            )
        }
    }
}
