package xyz.funkybit.integrationtests.testutils

import org.awaitility.kotlin.await
import org.http4k.client.WebsocketClient
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.provider.Arguments
import org.web3j.crypto.Credentials
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.model.db.BlockchainTransactionStatus
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainSettlementBatchEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderExecutionEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.SettlementBatchEntity
import xyz.funkybit.core.model.db.SettlementBatchStatus
import xyz.funkybit.core.model.db.SettlementBatchTable
import xyz.funkybit.core.model.db.SettlementStatus
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.TradeId
import xyz.funkybit.core.model.db.TradeTable
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.Faucet
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import xyz.funkybit.integrationtests.utils.assertBalances
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertLimitsMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyOrdersMessageReceived
import xyz.funkybit.integrationtests.utils.assertMyTradesMessageReceived
import xyz.funkybit.integrationtests.utils.assertOrderBookMessageReceived
import xyz.funkybit.integrationtests.utils.assertPricesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.integrationtests.utils.subscribeToLimits
import xyz.funkybit.integrationtests.utils.subscribeToMyOrders
import xyz.funkybit.integrationtests.utils.subscribeToMyTrades
import xyz.funkybit.integrationtests.utils.subscribeToOrderBook
import xyz.funkybit.integrationtests.utils.subscribeToPrices
import xyz.funkybit.integrationtests.utils.verifyApiReturnsSameLimits
import java.time.Duration

open class OrderBaseTest {

    companion object {
        lateinit var symbols: Map<String, SymbolInfo>
        lateinit var btc: SymbolInfo
        lateinit var btc2: SymbolInfo
        lateinit var eth: SymbolInfo
        lateinit var eth2: SymbolInfo
        lateinit var usdc: SymbolInfo
        lateinit var usdc2: SymbolInfo
        lateinit var dai: SymbolInfo
        lateinit var btcEthMarket: Market
        lateinit var btcEth2Market: Market
        lateinit var btc2Eth2Market: Market
        lateinit var btcUsdcMarket: Market
        lateinit var btc2Usdc2Market: Market
        lateinit var usdcDaiMarket: Market
        lateinit var usdc2Dai2Market: Market
        lateinit var btcbtc2Market: Market
        lateinit var chainIdBySymbol: Map<String, ChainId>

        @JvmStatic
        @BeforeAll
        fun loadSymbols() {
            val config = TestApiClient.getConfiguration()
            val(chain1, chain2) = config.chains.map { it.id }
            usdcDaiMarket = config.markets.first { it.id.value == "USDC:$chain1/DAI:$chain1" }
            usdc2Dai2Market = config.markets.first { it.id.value == "USDC:$chain2/DAI:$chain2" }
            btcEthMarket = config.markets.first { it.id.value == "BTC:$chain1/ETH:$chain1" }
            btcEth2Market = config.markets.first { it.id.value == "BTC:$chain1/ETH:$chain2" }
            btc2Eth2Market = config.markets.first { it.id.value == "BTC:$chain2/ETH:$chain2" }
            btcUsdcMarket = config.markets.first { it.id.value == "BTC:$chain1/USDC:$chain1" }
            btc2Usdc2Market = config.markets.first { it.id.value == "BTC:$chain2/USDC:$chain2" }
            btcbtc2Market = config.markets.first { it.id.value == "BTC:$chain1/BTC:$chain2" }
            symbols = config.chains.flatMap { it.symbols }.associateBy { it.name }
            btc = symbols.getValue("BTC:$chain1")
            btc2 = symbols.getValue("BTC:$chain2")
            eth = symbols.getValue("ETH:$chain1")
            eth2 = symbols.getValue("ETH:$chain2")
            usdc = symbols.getValue("USDC:$chain1")
            usdc2 = symbols.getValue("USDC:$chain2")
            dai = symbols.getValue("DAI:$chain1")
            chainIdBySymbol = config.chains.map { chain -> chain.symbols.map { it.name to chain.id } }.flatten().toMap()
        }

        @JvmStatic
        fun chainIndices() = listOf(
            Arguments.of(0),
            Arguments.of(1),
        )

        @JvmStatic
        fun swapEntries() = listOf(
            Arguments.of(Triple(btcEthMarket, btc, eth), "3", "3", "17.5", listOf("0.2"), true),
            Arguments.of(Triple(btcUsdcMarket, btc, usdc), "10000", "10000", "64000.000", listOf("0.05", "0.05", "0.10"), true),
            Arguments.of(Triple(btcbtc2Market, btc, btc2), "0.12", "0.11", "0.998", listOf("0.2"), true),
            Arguments.of(Triple(btcbtc2Market, btc, btc2), "0.12", "0.11", "0.998", listOf("0.01"), false),
        )
    }

    fun setupTrader(
        marketId: MarketId,
        airdrops: List<AssetAmount>,
        deposits: List<AssetAmount>,
        subscribeToOrderBook: Boolean = true,
        subscribeToPrices: Boolean = true,
        subscribeToLimits: Boolean = true,
    ): Triple<TestApiClient, Wallet, WsClient> {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val wsClient = WebsocketClient.blocking(apiClient.authToken).apply {
            if (subscribeToOrderBook) {
                subscribeToOrderBook(marketId)
                assertOrderBookMessageReceived(marketId)
            }

            if (subscribeToPrices) {
                subscribeToPrices(marketId)
                assertPricesMessageReceived(marketId)
            }

            subscribeToMyOrders()
            assertMyOrdersMessageReceived()

            subscribeToMyTrades()
            assertMyTradesMessageReceived()

            subscribeToBalances()
            assertBalancesMessageReceived()

            if (subscribeToLimits) {
                subscribeToLimits()
                assertLimitsMessageReceived().also { wsMessage ->
                    verifyApiReturnsSameLimits(apiClient, wsMessage)
                }
            }
        }

        airdrops.forEach {
            val chainId = chainIdBySymbol.getValue(it.symbol.name)
            if (chainId != wallet.currentChainId) {
                wallet.switchChain(chainId)
            }
            if (it.symbol.contractAddress == null) {
                Faucet.fundAndMine(wallet.address, it.inFundamentalUnits, chainId)
            } else {
                wallet.mintERC20AndMine(it)
            }
        }

        deposits.forEach {
            val chainId = chainIdBySymbol.getValue(it.symbol.name)
            if (chainId != wallet.currentChainId) {
                wallet.switchChain(chainId)
            }
            wallet.depositAndMine(it)
            wsClient.assertBalancesMessageReceived()
            if (subscribeToLimits) {
                wsClient.assertLimitsMessageReceived().also { wsMessage ->
                    verifyApiReturnsSameLimits(apiClient, wsMessage)
                }
            }
        }

        assertBalances(deposits.map { ExpectedBalance(it) }, apiClient.getBalances().balances)

        return Triple(apiClient, wallet, wsClient)
    }

    fun waitForSettlementToFinishWithForking(tradeIds: List<TradeId>, rollbackSettlement: Boolean = false) {
        waitFor {
            transaction {
                TradeEntity[tradeIds.first()].settlementBatchGuid != null
            }
        }

        val chainBatchGuid = transaction { TradeEntity[tradeIds.first()].settlementBatch!!.chainBatches.first().guid }

        // wait for the prepareTx to be submitted
        waitFor {
            transaction {
                ChainSettlementBatchEntity[chainBatchGuid].preparationTx.status == BlockchainTransactionStatus.Submitted
            }
        }

        // rolling back the onchain prepared batch and changing the tx hash, so it's not found simulates a fork since
        // the preparation tx will need to be resubmitted again
        // Not rolling back on chain but changing the tx hash simulates anvil restarting
        if (rollbackSettlement) {
            val submitterWallet = Wallet(TestApiClient(ecKeyPair = Credentials.create("0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba").ecKeyPair))
            submitterWallet.switchChain(transaction { ChainSettlementBatchEntity[chainBatchGuid].chainId.value })
            submitterWallet.rollbackSettlement()
        }

        // now change the hash in DB - this will cause it to think the tx does not exist
        transaction {
            ChainSettlementBatchEntity[chainBatchGuid].preparationTx.txHash = TxHash("0x6d37aaf942f1679e7c34d241859017d5caf42f57f7c1b4f1f0c149c2649bb822")
        }

        waitFor {
            Faucet.mine()
            transaction {
                ChainSettlementBatchEntity[chainBatchGuid].submissionTx?.let {
                    listOf(BlockchainTransactionStatus.Submitted, BlockchainTransactionStatus.Confirmed, BlockchainTransactionStatus.Completed).contains(it.status)
                } ?: false
            }
        }

        // now change the submission hash - simulates anvil restarting
        transaction {
            ChainSettlementBatchEntity[chainBatchGuid].submissionTx!!.txHash = TxHash("0x6d37aaf942f1679e7c34d241859017d5caf42f57f7c1b4f1f0c149c2649bb833")
        }

        waitForSettlementToFinish(tradeIds)
    }

    fun waitForPendingTradeAndMarkFailed(orderId: OrderId): Boolean {
        return try {
            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(10))
                .pollInterval(Duration.ofMillis(10))
                .atMost(Duration.ofMillis(10000L))
                .until {
                    val trades = getTradesForOrders(listOf(orderId))
                    transaction {
                        if (trades.isNotEmpty() && trades[0].settlementStatus == SettlementStatus.Pending) {
                            trades[0].settlementStatus = SettlementStatus.PendingRollback
                            true
                        } else {
                            false
                        }
                    }
                }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun waitForSettlementToFinish(tradeIds: List<TradeId>, expectedStatus: SettlementStatus = SettlementStatus.Completed) {
        waitFor {
            Faucet.mine()
            transaction {
                TradeEntity.count(TradeTable.guid.inList(tradeIds) and TradeTable.settlementStatus.eq(expectedStatus))
            } == tradeIds.size.toLong()
        }
    }

    fun waitForSettlementBatchToFinish() {
        waitFor {
            transaction {
                SettlementBatchEntity.count(SettlementBatchTable.status.neq(SettlementBatchStatus.Completed)) == 0L
            }
        }
    }

    fun getTradesForOrders(orderIds: List<OrderId>): List<TradeEntity> {
        return transaction {
            OrderExecutionEntity.findForOrders(orderIds).map { it.trade }
        }.sortedBy { it.sequenceId }
    }
}
