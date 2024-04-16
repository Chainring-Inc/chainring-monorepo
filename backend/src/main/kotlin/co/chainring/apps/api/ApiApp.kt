package co.chainring.apps.api

import co.chainring.apps.BaseApp
import co.chainring.apps.api.middleware.HttpTransactionLogger
import co.chainring.apps.api.middleware.RequestProcessingExceptionHandler
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.db.DbConfig
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.services.ExchangeService
import co.chainring.core.websocket.Broadcaster
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.ApiServer
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.Method
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Uri
import org.http4k.core.then
import org.http4k.filter.AllowAll
import org.http4k.filter.CorsPolicy
import org.http4k.filter.OriginPolicy
import org.http4k.filter.ServerFilters
import org.http4k.filter.ZipkinTraces
import org.http4k.format.Argo
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.websockets
import org.http4k.server.Netty
import org.http4k.server.PolyHandler
import org.http4k.server.ServerConfig
import org.http4k.server.asServer
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.MDC
import java.time.Duration.ofSeconds

data class ApiAppConfig(
    val httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 9000,
    val dbConfig: DbConfig = DbConfig(),
    val blockchainClientConfig: BlockchainClientConfig = BlockchainClientConfig(),
)

val requestContexts = RequestContexts()

class ApiApp(config: ApiAppConfig = ApiAppConfig()) : BaseApp(config.dbConfig) {
    override val logger = KotlinLogging.logger {}

    private val corsPolicy = CorsPolicy(
        originPolicy = OriginPolicy.AllowAll(),
        headers = listOf("Content-Type", "Authorization", "X-IsAPI"),
        methods = listOf(
            Method.DELETE,
            Method.GET,
            Method.PATCH,
            Method.POST,
            Method.PUT,
        ),
    )

    private val blockchainClient = BlockchainClient(config.blockchainClientConfig)
    private val sequencerClient = SequencerClient()
    private val broadcaster = Broadcaster()

    private val exchangeService = ExchangeService(blockchainClient, sequencerClient, broadcaster)

    private val withdrawalRoutes = WithdrawalRoutes(exchangeService)
    private val balanceRoutes = BalanceRoutes()

    private val orderRoutes = OrderRoutes(exchangeService)

    private val httpHandler = ServerFilters.InitialiseRequestContext(requestContexts)
        .then(ServerFilters.Cors(corsPolicy))
        .then(
            ServerFilters.RequestTracing(
                startReportFn = { _, z: ZipkinTraces ->
                    MDC.put("traceId", z.traceId.value)
                },
                endReportFn = { _, _, _ ->
                    MDC.remove("traceId")
                },
            ),
        )
        .then(HttpTransactionLogger())
        .then(RequestProcessingExceptionHandler(logger))
        .then(
            routes(
                "/health" bind Method.GET to { Response(Status.OK) },
                "/v1" bind
                    contract {
                        renderer = OpenApi3(
                            ApiInfo("ChainRing API", "0.1.0"),
                            Argo,
                            servers = listOf(ApiServer(Uri.of("https://api.chainring.finance"))),
                        )
                        descriptionPath = "/openapi.json"
                        routes +=
                            listOfNotNull(
                                ConfigRoutes.getConfiguration(),
                                orderRoutes.createOrder(),
                                orderRoutes.updateOrder(),
                                orderRoutes.cancelOrder(),
                                orderRoutes.getOrder(),
                                orderRoutes.listOrders(),
                                orderRoutes.cancelOpenOrders(),
                                orderRoutes.batchOrders(),
                                orderRoutes.listTrades(),
                                balanceRoutes.getBalances(),
                                withdrawalRoutes.getWithdrawal(),
                                withdrawalRoutes.createWithdrawal(),

                                // http api + websocket
                                // GET /v1/market/market_id/order-book
                                // GET /v1/market/market_id/trades
                            )
                    },
            ),
        )

    private val websocketApi = WebsocketApi(broadcaster)

    private val server = PolyHandler(
        httpHandler,
        websockets(websocketApi.connect()),
    )
        .asServer(Netty(config.httpPort, ServerConfig.StopMode.Graceful(ofSeconds(1))))

    override fun start() {
        startServer()
        broadcaster.start()
        updateContracts()
    }

    override fun stop() {
        logger.info { "Stopping" }
        super.stop()
        broadcaster.stop()
        server.stop()
        blockchainClient.stopTransactionSubmitter()
        logger.info { "Stopped" }
    }

    fun startServer() {
        logger.info { "Starting" }
        super.start()
        server.start()
        logger.info { "Started" }
    }

    fun updateContracts() {
        blockchainClient.updateContracts()
        blockchainClient.startTransactionSubmitter(
            exchangeService,
            transaction {
                WithdrawalEntity.findPending().map { it.toEip712Transaction() }
            },
        )
        blockchainClient.startDepositEventsConsumer(exchangeService)
    }
}
