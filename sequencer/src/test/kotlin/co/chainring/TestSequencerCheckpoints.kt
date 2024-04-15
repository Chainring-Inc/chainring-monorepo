package co.chainring

import co.chainring.sequencer.apps.GatewayApp
import co.chainring.sequencer.apps.GatewayConfig
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.Market
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.SequencerState
import co.chainring.sequencer.core.queueHome
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.GatewayGrpcKt
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.deposit
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.testutils.inSats
import co.chainring.testutils.inWei
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.test.runTest
import net.openhft.chronicle.queue.ChronicleQueue
import net.openhft.chronicle.queue.RollCycles
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.System.getenv
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.seconds

class TestSequencerCheckpoints {
    private val isOnCI = (getenv("CI_RUN") ?: "0") == "1"
    private val currentTime = AtomicLong(System.currentTimeMillis())
    private val testDirPath = Path.of(queueHome, "test")
    private val checkpointsPath = Path.of(testDirPath.toString(), "checkpoints")

    private val wallet1 = 123456789L.toWalletAddress()
    private val wallet2 = 555111555L.toWalletAddress()
    private val btc = Asset("BTC")
    private val eth = Asset("ETH")
    private val usdc = Asset("USDC")
    private val btcEthMarketId = MarketId("BTC/ETH")
    private val btcUsdcMarketId = MarketId("BTC/USDC")

    @BeforeEach
    fun beforeEach() {
        testDirPath.toFile().deleteRecursively()
        checkpointsPath.createDirectories()
    }

    @Test
    fun `test checkpoints`() = runTest {
        Assumptions.assumeFalse(isOnCI)

        val inputQueue = ChronicleQueue.singleBuilder(Path.of(testDirPath.toString(), "input"))
            .rollCycle(RollCycles.MINUTELY)
            .timeProvider(currentTime::get)
            .build()

        val outputQueue = ChronicleQueue.singleBuilder(Path.of(testDirPath.toString(), "output"))
            .rollCycle(RollCycles.MINUTELY)
            .timeProvider(currentTime::get)
            .build()

        val sequencedQueue = ChronicleQueue.singleBuilder(Path.of(testDirPath.toString(), "sequenced"))
            .rollCycle(RollCycles.MINUTELY)
            .timeProvider(currentTime::get)
            .build()

        assertQueueFilesCount(inputQueue, 0)
        assertCheckpointFilesCount(checkpointsPath, 0)

        val gatewayApp = GatewayApp(GatewayConfig(port = 5339), inputQueue, outputQueue, sequencedQueue)
        val sequencerApp = SequencerApp(inputQueue, outputQueue, checkpointsPath)

        try {
            sequencerApp.start()
            gatewayApp.start()

            val gateway = GatewayGrpcKt.GatewayCoroutineStub(
                ManagedChannelBuilder.forAddress("localhost", 5339).usePlaintext().build(),
            )

            assertTrue(
                gateway.addMarket(
                    market {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.tickSize = "0.05".toBigDecimal().toDecimalValue()
                        this.maxLevels = 1000
                        this.maxOrdersPerLevel = 1000
                        this.marketPrice = "17.525".toBigDecimal().toDecimalValue()
                        this.baseDecimals = 18
                        this.quoteDecimals = 18
                    },
                ).success,
            )

            // set balances
            assertTrue(
                gateway.applyBalanceBatch(
                    balanceBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.deposits.addAll(
                            listOf(
                                deposit {
                                    this.asset = btcEthMarketId.baseAsset().value
                                    this.wallet = wallet1.value
                                    this.amount = BigDecimal("1").inSats().toIntegerValue()
                                },
                                deposit {
                                    this.asset = btcEthMarketId.quoteAsset().value
                                    this.wallet = wallet1.value
                                    this.amount = BigDecimal("1").inWei().toIntegerValue()
                                },
                                deposit {
                                    this.asset = btcEthMarketId.quoteAsset().value
                                    this.wallet = wallet2.value
                                    this.amount = BigDecimal("1").inWei().toIntegerValue()
                                },
                            ),
                        )
                    },
                ).success,
            )

            currentTime.addAndGet(1.seconds.inWholeMilliseconds)

            // limit sell 1
            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            currentTime.addAndGet(60.seconds.inWholeMilliseconds)
            // next request will trigger a queue rollover

            // limit sell 2
            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.560").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            // limit sell 3
            assertTrue(
                gateway.applyOrderBatch(
                    orderBatch {
                        this.guid = UUID.randomUUID().toString()
                        this.marketId = btcEthMarketId.value
                        this.ordersToAdd.add(
                            order {
                                this.guid = Random.nextLong()
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.570").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                        )
                    },
                ).success,
            )

            // restart sequencer, it should recover from checkpoint
            sequencerApp.stop()
            sequencerApp.start()

            // market buy, should be matched
            gateway.applyOrderBatch(
                orderBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = btcEthMarketId.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = BigDecimal("0.0011").inSats().toIntegerValue()
                            this.price = BigDecimal.ZERO.toDecimalValue()
                            this.wallet = wallet2.value
                            this.type = Order.Type.MarketBuy
                        },
                    )
                },
            ).also {
                assertTrue(it.success)
                assertEquals(4, it.sequencerResponse.ordersChangedCount)

                it.sequencerResponse.ordersChangedList[0].also { marketBuyOrder ->
                    assertEquals(OrderDisposition.Filled, marketBuyOrder.disposition)
                }

                it.sequencerResponse.ordersChangedList[1].also { limitSellOrder1 ->
                    assertEquals(OrderDisposition.Filled, limitSellOrder1.disposition)
                }

                it.sequencerResponse.ordersChangedList[2].also { limitSellOrder2 ->
                    assertEquals(OrderDisposition.Filled, limitSellOrder2.disposition)
                }

                it.sequencerResponse.ordersChangedList[3].also { limitSellOrder3 ->
                    assertEquals(OrderDisposition.PartiallyFilled, limitSellOrder3.disposition)
                }
            }

            assertQueueFilesCount(inputQueue, 2)
            assertCheckpointFilesCount(checkpointsPath, 1)
            assertOutputQueueContainsNoDuplicates(outputQueue, expectedMessagesCount = 6)
        } finally {
            gatewayApp.stop()
            sequencerApp.stop()
        }
    }

    private fun assertQueueFilesCount(queue: ChronicleQueue, expectedCount: Long) {
        Files.list(Path.of(queue.fileAbsolutePath())).use { list ->
            assertEquals(
                expectedCount,
                list.filter { p ->
                    p.toString().endsWith(SingleChronicleQueue.SUFFIX)
                }.count(),
            )
        }
    }

    private fun assertCheckpointFilesCount(path: Path, expectedCount: Long) {
        Files.list(path).use { list ->
            assertEquals(expectedCount, list.count())
        }
    }

    private fun assertOutputQueueContainsNoDuplicates(outputQueue: ChronicleQueue, expectedMessagesCount: Int) {
        val processedRequestGuids = mutableListOf<Long>()
        val outputTailer = outputQueue.createTailer()
        val lastIndex = outputTailer.toEnd().index()
        outputTailer.toStart()
        while (true) {
            if (outputTailer.index() == lastIndex) {
                break
            }
            outputTailer.readingDocument().use {
                if (it.isPresent) {
                    it.wire()?.read()?.bytes { bytes ->
                        processedRequestGuids.add(
                            SequencerResponse.parseFrom(bytes.toByteArray()).sequence,
                        )
                    }
                }
            }
        }

        assertEquals(
            processedRequestGuids.distinct(),
            processedRequestGuids,
            "Output queue contains duplicate responses",
        )

        assertEquals(expectedMessagesCount, processedRequestGuids.size)
    }

    @Test
    fun `test state storing and loading - empty`() {
        verifySerialization(
            SequencerState(),
        )
    }

    @Test
    fun `test state storing and loading - single empty market`() {
        verifySerialization(
            SequencerState(
                balances = mutableMapOf(
                    wallet1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    wallet2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxLevels = 1000,
                        maxOrdersPerLevel = 1000,
                        marketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - market with no buy orders`() {
        verifySerialization(
            SequencerState(
                balances = mutableMapOf(
                    wallet1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    wallet2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxLevels = 1000,
                        maxOrdersPerLevel = 1000,
                        marketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.560").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.600").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach {
                            market.addOrder(it)
                        }
                    },
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - market with no sell orders`() {
        verifySerialization(
            SequencerState(
                balances = mutableMapOf(
                    wallet1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                    ),
                    wallet2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxLevels = 1000,
                        maxOrdersPerLevel = 1000,
                        marketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.3").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.4").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.5").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                        ).forEach {
                            market.addOrder(it)
                        }
                    },
                ),
            ),
        )
    }

    @Test
    fun `test state storing and loading - markets buy and sell orders`() {
        verifySerialization(
            SequencerState(
                balances = mutableMapOf(
                    wallet1 to mutableMapOf(
                        btc to BigDecimal("1").inSats(),
                        eth to BigDecimal("2").inWei(),
                        usdc to BigDecimal("10000").inWei(),
                    ),
                    wallet2 to mutableMapOf(
                        btc to BigDecimal("3").inSats(),
                        usdc to BigDecimal("10000").inWei(),
                    ),
                ),
                markets = mutableMapOf(
                    btcEthMarketId to Market(
                        id = btcEthMarketId,
                        tickSize = BigDecimal("0.05"),
                        maxLevels = 1000,
                        maxOrdersPerLevel = 1000,
                        marketPrice = BigDecimal("17.525"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.3").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.4").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.5").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 4
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.550").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 5
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.560").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 6
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("17.600").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach {
                            market.addOrder(it)
                        }
                    },
                    btcUsdcMarketId to Market(
                        id = btcUsdcMarketId,
                        tickSize = BigDecimal("1.00"),
                        maxLevels = 1000,
                        maxOrdersPerLevel = 1000,
                        marketPrice = BigDecimal("70000"),
                        baseDecimals = 18,
                        quoteDecimals = 18,
                    ).also { market ->
                        listOf(
                            order {
                                this.guid = 1
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("69997").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 2
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("69998").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 3
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("69999").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitBuy
                            },
                            order {
                                this.guid = 4
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("70001").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 5
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("70002").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                            order {
                                this.guid = 6
                                this.amount = BigDecimal("0.0005").inSats().toIntegerValue()
                                this.price = BigDecimal("70003").toDecimalValue()
                                this.wallet = wallet1.value
                                this.type = Order.Type.LimitSell
                            },
                        ).forEach {
                            market.addOrder(it)
                        }
                    },
                ),
            ),
        )
    }

    private fun verifySerialization(initialState: SequencerState) {
        checkpointsPath.toFile().deleteRecursively()
        checkpointsPath.createDirectories()

        val checkpointPath = Path.of(checkpointsPath.toString(), "1")
        initialState.persist(checkpointPath)

        val restoredState = SequencerState().apply { load(checkpointPath) }

        initialState.markets.values.forEach { initialStateMarket ->
            val restoredStateMarket = restoredState.markets.getValue(initialStateMarket.id)

            initialStateMarket.levels.forEach { initialStateLevel ->
                initialStateLevel.orders.forEachIndexed { i, initialStateOrder ->
                    assertEquals(
                        initialStateOrder,
                        restoredStateMarket.levels[initialStateLevel.levelIx].orders[i],
                        "Order mismatch at levelIx=${initialStateLevel.levelIx}, orderIx=$i",
                    )
                }
            }

            assertContentEquals(
                initialStateMarket.levels,
                restoredStateMarket.levels,
                "Levels in market ${initialStateMarket.id} don't match",
            )
        }

        assertEquals(initialState, restoredState)
    }
}