package co.chainring.tasks

import co.chainring.core.db.DbConfig
import co.chainring.core.db.connect
import co.chainring.core.model.db.BlockchainNonceEntity
import co.chainring.core.model.db.ChainEntity
import co.chainring.core.model.db.KeyValueStore
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OHLCEntity
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.SymbolId
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.tasks.fixtures.Fixtures
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
                ChainEntity.create(chain.id, chain.name, chain.jsonRpcUrl, chain.blockExplorerNetName, chain.blockExplorerUrl).flush()
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
                        withdrawalFee = symbol.withdrawalFee.toFundamentalUnits(symbol.decimals)
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
                        it.flush()
                        println("Updated symbol ${it.name} with guid=${it.guid.value}")
                    }
                }
            }

        }.associateBy { it.id.value }

        fixtures.markets.forEach { (baseSymbolId, quoteSymbolId, tickSize, marketPrice) ->
            val baseSymbol = symbolEntities.getValue(baseSymbolId)
            val quoteSymbol = symbolEntities.getValue(quoteSymbolId)

            when (val marketEntity = MarketEntity.findById(MarketId(baseSymbol, quoteSymbol))) {
                null -> {
                    MarketEntity
                        .create(baseSymbol, quoteSymbol, tickSize, marketPrice)
                        .also {
                            it.flush()
                            println("Created market ${it.guid.value}")

                            OHLCEntity.updateWith(
                                market = it.guid.value,
                                tradeTimestamp = Clock.System.now() - 1.hours,
                                tradePrice = marketPrice,
                                tradeAmount = BigInteger.ZERO,
                            )
                        }
                }

                else -> {
                    marketEntity.also {
                        it.baseSymbol = baseSymbol
                        it.quoteSymbol = quoteSymbol
                        it.tickSize = tickSize
                        it.flush()
                        println("Updated market ${it.guid.value}")
                    }
                }
            }
        }
    }
}

