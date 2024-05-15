package co.chainring.sequencer.core

import co.chainring.sequencer.proto.BalancesCheckpoint
import co.chainring.sequencer.proto.BalancesCheckpointKt.BalanceKt.consumption
import co.chainring.sequencer.proto.BalancesCheckpointKt.balance
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.MetaInfoCheckpoint
import co.chainring.sequencer.proto.StateDump
import co.chainring.sequencer.proto.balancesCheckpoint
import co.chainring.sequencer.proto.feeRates
import co.chainring.sequencer.proto.metaInfoCheckpoint
import co.chainring.sequencer.proto.stateDump
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.system.measureNanoTime

typealias BalanceByAsset = MutableMap<Asset, BigInteger>
typealias ConsumedByAsset = MutableMap<Asset, MutableMap<MarketId, BigInteger>>

data class FeeRates(
    val maker: FeeRate,
    val taker: FeeRate,
) {
    companion object {
        fun fromPercents(maker: Double, taker: Double): FeeRates =
            FeeRates(
                maker = FeeRate.fromPercents(maker),
                taker = FeeRate.fromPercents(taker),
            )
    }
}

data class SequencerState(
    val markets: MutableMap<MarketId, Market> = mutableMapOf(),
    val balances: MutableMap<WalletAddress, BalanceByAsset> = mutableMapOf(),
    val consumed: MutableMap<WalletAddress, ConsumedByAsset> = mutableMapOf(),
    var feeRates: FeeRates = FeeRates(maker = FeeRate.zero, taker = FeeRate.zero),
) {
    private val logger = KotlinLogging.logger {}

    fun clear() {
        balances.clear()
        markets.clear()
        consumed.clear()
        feeRates = FeeRates(maker = FeeRate.zero, taker = FeeRate.zero)
    }

    fun load(sourceDir: Path) {
        measureNanoTime {
            FileInputStream(Path.of(sourceDir.toString(), "balances").toFile()).use { inputStream ->
                val balancesCheckpoint = BalancesCheckpoint.parseFrom(inputStream)
                balancesCheckpoint.balancesList.forEach { balanceCheckpoint ->
                    val walletAddress = balanceCheckpoint.wallet.toWalletAddress()
                    val asset = balanceCheckpoint.asset.toAsset()
                    balances.getOrPut(walletAddress) { mutableMapOf() }[asset] = balanceCheckpoint.amount.toBigInteger()
                    if (balanceCheckpoint.consumedCount > 0) {
                        consumed.getOrPut(walletAddress) { mutableMapOf() }.getOrPut(asset) { mutableMapOf() }.putAll(
                            balanceCheckpoint.consumedList.associate {
                                it.marketId.toMarketId() to it.consumed.toBigInteger()
                            },
                        )
                    }
                }
            }
        }.let {
            logger.debug { "load of balances took ${it / 1000}us" }
        }

        measureNanoTime {
            val metaInfoCheckpoint = FileInputStream(Path.of(sourceDir.toString(), "metainfo").toFile()).use { inputStream ->
                MetaInfoCheckpoint.parseFrom(inputStream)
            }

            feeRates = FeeRates(
                maker = FeeRate(metaInfoCheckpoint.makerFeeRate),
                taker = FeeRate(metaInfoCheckpoint.takerFeeRate),
            )

            val marketIds = metaInfoCheckpoint.marketsList.map(::MarketId)

            marketIds.forEach { marketId ->
                val marketCheckpointFileName = "market_${marketId.baseAsset()}_${marketId.quoteAsset()}"
                FileInputStream(Path.of(sourceDir.toString(), marketCheckpointFileName).toFile()).use { inputStream ->
                    val marketCheckpoint = MarketCheckpoint.parseFrom(inputStream)
                    val market = Market.fromCheckpoint(marketCheckpoint)
                    markets[market.id] = market
                }
            }
        }.let {
            logger.debug { "load of ${markets.size} markets took ${it / 1000}us" }
        }
    }

    fun persist(destinationDir: Path) {
        destinationDir.createDirectories()

        // we are writing a list of markets into a separate file first so that
        // when loading we could be sure that checkpoint files for all markets are present
        FileOutputStream(Path.of(destinationDir.toString(), "metainfo").toFile()).use { outputStream ->
            val marketIds = this.markets.keys.map { it.value }.sorted()
            metaInfoCheckpoint {
                this.markets.addAll(marketIds)
                this.makerFeeRate = feeRates.maker.value
                this.takerFeeRate = feeRates.taker.value
            }.writeTo(outputStream)
        }

        measureNanoTime {
            FileOutputStream(Path.of(destinationDir.toString(), "balances").toFile()).use { outputStream ->
                getBalancesCheckpoint().writeTo(outputStream)
            }
        }.let {
            logger.debug { "persist of balances took ${it / 1000}us" }
        }

        measureNanoTime {
            markets.forEach { (id, market) ->
                val fileName = "market_${id.baseAsset()}_${id.quoteAsset()}"

                FileOutputStream(Path.of(destinationDir.toString(), fileName).toFile()).use { outputStream ->
                    market.toCheckpoint().writeTo(outputStream)
                }
            }
        }.let {
            logger.debug { "persist of ${markets.size} markets took ${it / 1000}us" }
        }
    }

    fun getDump(): StateDump {
        val marketsMap = markets
        return stateDump {
            this.balances.addAll(getBalancesCheckpoint().balancesList)
            marketsMap.forEach { (_, market) ->
                this.markets.add(market.toCheckpoint())
            }
            this.feeRates = feeRates {
                this.maker = this@SequencerState.feeRates.maker.value
                this.taker = this@SequencerState.feeRates.taker.value
            }
        }
    }

    private fun getBalancesCheckpoint(): BalancesCheckpoint {
        val balancesMap = balances

        return balancesCheckpoint {
            balancesMap.forEach { (wallet, walletBalances) ->
                walletBalances.forEach { (asset, amount) ->
                    this.balances.add(
                        balance {
                            this.wallet = wallet.value
                            this.asset = asset.value
                            this.amount = amount.toIntegerValue()
                            this.consumed.addAll(
                                this@SequencerState.consumed.getOrDefault(wallet, mapOf()).getOrDefault(asset, mapOf()).map {
                                    consumption {
                                        this.marketId = it.key.value
                                        this.consumed = it.value.toIntegerValue()
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
