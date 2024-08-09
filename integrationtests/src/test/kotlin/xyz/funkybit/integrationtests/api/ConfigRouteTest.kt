package xyz.funkybit.integrationtests.api

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.FaucetMode
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.FeeRate
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.tasks.fixtures.toChainSymbol
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertNull

@ExtendWith(AppUnderTestRunner::class)
class ConfigRouteTest {
    @Test
    fun testConfiguration() {
        val apiClient = TestApiClient()

        val config = apiClient.getConfiguration()
        assertEquals(config.evmChains.size, 2)
        ChainManager.blockchainConfigs.forEach { clientConfig ->
            val client = BlockchainClient(clientConfig)
            val chainConfig = config.chains.first { it.id == client.chainId }
            assertEquals(chainConfig.contracts.size, 1)
            assertEquals(chainConfig.contracts[0].name, ContractType.Exchange.name)
            val exchangeContract = client.loadExchangeContract(chainConfig.contracts[0].address)
            assertEquals(exchangeContract.version.send().toInt(), 1)

            val ethSymbol = chainConfig.symbols.first { it.name == "ETH".toChainSymbol(chainConfig.id) }
            assertEquals(BigDecimal("0.0003").toFundamentalUnits(ethSymbol.decimals.toInt()), ethSymbol.withdrawalFee)
            val usdcSymbol = chainConfig.symbols.first { it.name == "USDC".toChainSymbol(chainConfig.id) }
            assertEquals(BigDecimal("1").toFundamentalUnits(usdcSymbol.decimals.toInt()), usdcSymbol.withdrawalFee)

            val nativeToken = chainConfig.symbols.first { it.contractAddress == null }
            assertEquals("BTC".toChainSymbol(chainConfig.id), nativeToken.name)
            assertEquals(18.toUByte(), nativeToken.decimals)
            assertEquals(BigDecimal("0.00002").toFundamentalUnits(nativeToken.decimals.toInt()), nativeToken.withdrawalFee)
        }

        assertEquals(
            FeeRates(maker = FeeRate.fromPercents(1.0), taker = FeeRate.fromPercents(2.0)),
            config.feeRates,
        )

        assertEquals(
            config.markets.associate { it.id to it.minFee },
            TestApiClient.getSequencerStateDump().markets.associate { MarketId(it.id) to it.minFee },
        )
    }

    @Test
    fun testBitcoinConfiguration() {
        Assumptions.assumeTrue((System.getenv("INTEGRATION_RUN") ?: "0") != "1")

        val apiClient = TestApiClient()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.filter { it.networkType == NetworkType.Bitcoin }.size, 1)
        val btcChainConfig = config.chains.single { it.networkType == NetworkType.Bitcoin }
        assertEquals(btcChainConfig.contracts.size, 1)
        assertEquals(btcChainConfig.contracts[0].name, ContractType.Exchange.name)

        val btcSymbol = btcChainConfig.symbols.first { it.name == "BTC".toChainSymbol(btcChainConfig.id) }
        assertEquals(BigDecimal("0.00000002").toFundamentalUnits(btcSymbol.decimals.toInt()), btcSymbol.withdrawalFee)
        assertNull(btcSymbol.contractAddress)
        assertEquals(8.toUByte(), btcSymbol.decimals)
    }

    @Test
    fun testAccountConfiguration() {
        val apiClient = TestApiClient()
        val config = apiClient.getConfiguration()
        assertEquals(emptyList<SymbolInfo>(), apiClient.getAccountConfiguration().newSymbols)
        // add a symbol which can be added to a wallet
        val symbol = transaction {
            SymbolEntity.create("RING", config.chains[0].id, Address.generate(), 18u, "Test funkybit Token", addToWallets = true, withdrawalFee = BigInteger.ZERO)
        }
        try {
            assertEquals(
                listOf(
                    SymbolInfo(
                        symbol.name,
                        symbol.description,
                        symbol.contractAddress,
                        symbol.decimals,
                        symbol.faucetSupported(
                            FaucetMode.AllSymbols,
                        ),
                        symbol.iconUrl,
                        symbol.withdrawalFee,
                    ),
                ),
                apiClient.getAccountConfiguration().newSymbols,
            )

            // mark it as already added for our wallet
            apiClient.markSymbolAsAdded(symbol.name)

            // now it doesn't show up
            assertEquals(emptyList<SymbolInfo>(), apiClient.getAccountConfiguration().newSymbols)
        } finally {
            transaction {
                symbol.delete()
            }
        }
    }
}
