package xyz.funkybit.tasks

import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.connect
import xyz.funkybit.core.model.db.BlockchainNonceEntity
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OHLCEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.tasks.fixtures.Fixtures
import java.math.BigInteger
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

fun seedDatabase(fixtures: Fixtures, symbolContractAddresses: List<SymbolContractAddress>) {
    val db = Database.connect(DbConfig())
    TransactionManager.defaultDatabase = db

    transaction {
        fixtures.feeRates.persist()

        fixtures.chains.forEach { chain ->
            if (ChainEntity.findById(chain.id) == null) {
                ChainEntity.create(chain.id, chain.name, chain.jsonRpcUrl, chain.blockExplorerNetName, chain.blockExplorerUrl, chain.networkType).flush()
                println("Created chain ${chain.name} with id=${chain.id}")
            }
            if (BlockchainNonceEntity.findByKeyAndChain(chain.submitterAddress, chain.id) == null) {
                BlockchainNonceEntity.create(chain.submitterAddress, chain.id)
            }
        }

        val symbolEntities = fixtures.symbols.map { symbol ->
            val contractAddress = if (symbol.isNative) null else symbolContractAddresses.first { it.symbolId == symbol.id }.address

            when(val symbolEntity = SymbolEntity.findById(SymbolId(symbol.chainId, symbol.name))) {
                null -> {
                    SymbolEntity.create(
                        symbol.name.replace(Regex(":.*"), ""),
                        symbol.chainId,
                        contractAddress = contractAddress,
                        decimals = symbol.decimals.toUByte(),
                        description = symbol.description,
                        withdrawalFee = symbol.withdrawalFee.toFundamentalUnits(symbol.decimals),
                        iconUrl = symbol.iconUrl
                    ).also {
                        it.flush()
                        println("Created symbol ${symbol.name} with guid=${it.guid.value}")
                    }
                }
                else -> {
                    symbolEntity.also {
                        it.contractAddress = contractAddress
                        it.decimals = symbol.decimals.toUByte()
                        it.withdrawalFee = symbol.withdrawalFee.toFundamentalUnits(symbol.decimals)
                        it.iconUrl = symbol.iconUrl
                        it.flush()
                        println("Updated symbol ${it.name} with guid=${it.guid.value}")
                    }
                }
            }

        }.associateBy { it.id.value }

        fixtures.markets.forEach { (baseSymbolId, quoteSymbolId, tickSize, lastPrice, minFee) ->
            val baseSymbol = symbolEntities.getValue(baseSymbolId)
            val quoteSymbol = symbolEntities.getValue(quoteSymbolId)

            when (val marketEntity = MarketEntity.findById(MarketId(baseSymbol, quoteSymbol))) {
                null -> {
                    MarketEntity
                        .create(
                            baseSymbol,
                            quoteSymbol,
                            tickSize,
                            lastPrice,
                            "seed",
                            minFee.toFundamentalUnits(quoteSymbol.decimals)
                        )
                        .also {
                            it.flush()
                            println("Created market ${it.guid.value}")

                            OHLCEntity.updateWith(
                                market = it.guid.value,
                                tradeTimestamp = Clock.System.now() - 1.hours,
                                tradePrice = lastPrice,
                                tradeAmount = BigInteger.ZERO,
                            )
                        }
                }

                else -> {
                    marketEntity.also {
                        it.baseSymbol = baseSymbol
                        it.quoteSymbol = quoteSymbol
                        it.tickSize = tickSize
                        it.minFee = minFee.toFundamentalUnits(quoteSymbol.decimals)
                        it.flush()
                        println("Updated market ${it.guid.value}")
                    }
                }
            }
        }
    }
}

