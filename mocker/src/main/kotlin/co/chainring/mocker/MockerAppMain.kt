package co.chainring.mocker

import co.chainring.apps.api.middleware.HttpTransactionLogger
import co.chainring.apps.api.middleware.RequestProcessingExceptionHandler
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.model.db.MarketId
import co.chainring.core.utils.TraceRecorder
import co.chainring.mocker.core.DeterministicHarmonicPriceMovement
import co.chainring.mocker.core.Maker
import co.chainring.mocker.core.Taker
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.time.Duration
import java.util.*
import kotlin.concurrent.timerTask
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import org.http4k.core.*
import org.http4k.filter.ServerFilters
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Netty
import org.http4k.server.PolyHandler
import org.http4k.server.ServerConfig
import org.http4k.server.asServer
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

fun main() {
    val logger = KotlinLogging.logger {}
    logger.info { "Starting mocker app" }

    try {
        MockerApp().start()
    } catch (e: Throwable) {
        logger.error(e) { "Failed to start mocker app" }
        exitProcess(1)
    }
}

data class MarketParams(
    var desiredTakersCount: Int,
    var priceBaseline: BigDecimal,
    var initialBaseBalance: BigDecimal,
    var makerPrivateKeyHex: String,
    val makers: MutableList<Maker> = mutableListOf(),
    val takers: MutableList<Taker> = mutableListOf()
)

class MockerApp(
    httpPort: Int = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8000,
    config: List<String> = System.getenv("MARKETS")?.split(',') ?: listOf("BTC/ETH")
) {
    private val logger = KotlinLogging.logger {}
    private val marketsConfig = mutableMapOf<MarketId, MarketParams>()
    private val marketsPriceFunctions = mutableMapOf<MarketId, DeterministicHarmonicPriceMovement>()

    init {
        config.forEach {
            marketsConfig[MarketId(it)] = MarketParams(
                desiredTakersCount = System.getenv("${it}_TAKERS")?.toIntOrNull() ?: 5,
                priceBaseline = System.getenv("${it}_PRICE_BASELINE")?.toBigDecimalOrNull() ?: BigDecimal.TEN,
                initialBaseBalance = System.getenv("${it}_INITIAL_BASE_BALANCE")?.toBigDecimalOrNull() ?: BigDecimal.ONE,
                makerPrivateKeyHex = System.getenv("${it}_MAKER_PRIVATE_KEY_HEX") ?: ("0x" + Keys.createEcKeyPair().privateKey.toString(16)),
            )
        }
    }

    private val getConfigLens = Body.auto<Map<MarketId, Map<String, Int>>>().toLens()
    private val updateConfigLens = Body.auto<Map<String, Int>>().toLens()
    private val marketIdLens = Path.of("marketId")

    private val httpHandler = ServerFilters.InitialiseRequestContext(RequestContexts())
        .then(HttpTransactionLogger())
        .then(RequestProcessingExceptionHandler(logger))
        .then(
            routes(
                "/health" bind Method.GET to { Response(Status.OK) },
                "/v1/config" bind Method.GET to { _: Request ->
                    val configResponse = marketsConfig.mapValues { (_, params) ->
                        mapOf(
                            "desiredTakersCount" to params.desiredTakersCount
                        )
                    }
                    Response(Status.OK).with(getConfigLens of configResponse)
                },
                "/v1/config/{marketId}" bind Method.POST to { request: Request ->
                    val marketId = MarketId(marketIdLens(request))
                    val newConfig = updateConfigLens(request)
                    val currentParams = marketsConfig[marketId]

                    if (currentParams != null) {
                        newConfig["desiredTakersCount"]?.let { currentParams.desiredTakersCount = it }

                        updateMarketActors()

                        Response(Status.OK).body("Updated configuration for market: $marketId")
                    } else {
                        Response(Status.NOT_FOUND).body("Market not found: $marketId")
                    }
                }
            ),
        )

    private fun updateMarketActors() {
        val currentMarkets = ApiClient().getConfiguration().markets.associateBy { it.id }

        marketsConfig.forEach { (marketId, params) ->
            logger.info { "Updating configuration for $marketId: (makers 1, desired takers: ${params.desiredTakersCount})" }
            val market = currentMarkets[marketId] ?: run {
                logger.info { "Market $marketId not found. Skipping." }
                return@forEach
            }

            val priceFunction = marketsPriceFunctions.getOrPut(marketId) {
                DeterministicHarmonicPriceMovement.generateRandom(
                    initialValue = params.priceBaseline.toDouble(),
                    maxFluctuation = market.tickSize.toDouble() * 25
                )
            }

            // Start maker
            if (params.makers.size == 0) {
                params.makers.add(
                    startMaker(
                        market,
                        params.initialBaseBalance * BigDecimal(100),
                        params.initialBaseBalance * params.priceBaseline * BigDecimal(100),
                        keyPair = ECKeyPair.create(Numeric.toBigInt(params.makerPrivateKeyHex))
                    )
                )
            }

            // Adjust takers count
            while (params.takers.size < params.desiredTakersCount) {
                params.takers.add(startTaker(market, params.initialBaseBalance, params.initialBaseBalance * params.priceBaseline, priceFunction))
            }
            while (params.takers.size > params.desiredTakersCount) {
                params.takers.removeAt(params.takers.size - 1).stop()
            }
        }
    }

    private val server = PolyHandler(
        httpHandler
    ).asServer(Netty(httpPort, ServerConfig.StopMode.Graceful(Duration.ofSeconds(1))))

    fun start() {
        logger.info { "Starting" }

        server.start()

        // schedule printings stats
        Timer().also {
            val metricsTask = timerTask {
                val totalMakers = marketsConfig.map { it.value.makers.size }.sum()
                val totalTakers = marketsConfig.map { it.value.takers.size }.sum()
                logger.debug {
                    TraceRecorder.full.generateStatsAndFlush(
                        header = "Running $totalMakers makers and $totalTakers takers in ${marketsConfig.size} markets"
                    )
                }
            }
            it.scheduleAtFixedRate(
                metricsTask,
                5.minutes.inWholeMilliseconds,
                5.minutes.inWholeMilliseconds,
            )
        }

        // start makers/takers
        updateMarketActors()

        logger.info { "Started" }
    }
}
