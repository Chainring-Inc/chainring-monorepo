package co.chainring.integrationtests.api

import co.chainring.apps.api.FaucetMode
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import co.chainring.core.model.FeeRate
import co.chainring.core.model.db.FeeRates
import co.chainring.core.model.db.SymbolEntity
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.tasks.fixtures.toChainSymbol
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class ConfigRouteTest {
    @Test
    fun testConfiguration() {
        val apiClient = TestApiClient()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.size, 2)
        ChainManager.blockchainConfigs.forEachIndexed { index, clientConfig ->
            val client = BlockchainClient(clientConfig)
            val chainConfig = config.chains.first { it.id == client.chainId }
            assertEquals(chainConfig.contracts.size, 1)
            assertEquals(chainConfig.contracts[0].name, ContractType.Exchange.name)
            val exchangeContract = client.loadExchangeContract(chainConfig.contracts[0].address)
            assertEquals(exchangeContract.version.send().toInt(), 1)

            assertNotNull(chainConfig.symbols.firstOrNull { it.name == "ETH".toChainSymbol(chainConfig.id) })
            assertNotNull(chainConfig.symbols.firstOrNull { it.name == "USDC".toChainSymbol(chainConfig.id) })

            val nativeToken = chainConfig.symbols.first { it.contractAddress == null }
            assertEquals("BTC".toChainSymbol(chainConfig.id), nativeToken.name)
            assertEquals(18.toUByte(), nativeToken.decimals)
        }

        assertEquals(
            FeeRates(maker = FeeRate.fromPercents(1.0), taker = FeeRate.fromPercents(2.0)),
            config.feeRates,
        )
    }

    @Test
    fun testAccountConfiguration() {
        val apiClient = TestApiClient()
        val config = apiClient.getConfiguration()
        assertEquals(emptyList<SymbolInfo>(), apiClient.getAccountConfiguration().newSymbols)
        // add a symbol which can be added to a wallet
        val symbol = transaction {
            SymbolEntity.create("RING", config.chains[0].id, Address.generate(), 18u, "Test ChainRing Token", addToWallets = true)
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
