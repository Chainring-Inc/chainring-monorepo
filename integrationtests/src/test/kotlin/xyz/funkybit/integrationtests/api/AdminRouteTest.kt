package xyz.funkybit.integrationtests.api

import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.AdminRoutes
import xyz.funkybit.apps.api.FaucetMode
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.Role
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.FeeRate
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.SymbolId
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertError
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class AdminRouteTest {
    private val sequencerResponseTimeout = Duration.ofMillis(1000)

    @Test
    fun `test admin management`() {
        val apiClient = TestApiClient()

        apiClient.tryListAdmins().assertError(ApiError(ReasonCode.AuthenticationError, "Access denied"))
        assertEquals(Role.User, apiClient.getAccountConfiguration().role)
        transaction {
            WalletEntity.getOrCreate(apiClient.address).isAdmin = true
        }
        assertEquals(listOf(apiClient.address), apiClient.listAdmins())
        assertEquals(Role.Admin, apiClient.getAccountConfiguration().role)

        val apiClient2 = TestApiClient()
        apiClient.addAdmin(apiClient2.address)
        apiClient2.removeAdmin(apiClient.address)
        apiClient.tryListAdmins().assertError(ApiError(ReasonCode.AuthenticationError, "Access denied"))
        assertEquals(Role.User, apiClient.getAccountConfiguration().role)
    }

    @Test
    fun `test set fee rates`() {
        val apiClient = TestApiClient()

        val feeRates = apiClient.getConfiguration().feeRates
        transaction {
            WalletEntity.getOrCreate(apiClient.address).isAdmin = true
        }

        val newFeeRates = FeeRates(FeeRate(feeRates.maker.value + 1L), FeeRate(feeRates.taker.value + 2L))
        apiClient.setFeeRates(newFeeRates)
        await.atMost(sequencerResponseTimeout).untilAsserted {
            assertEquals(newFeeRates, apiClient.getConfiguration().feeRates)
        }
    }

    @Test
    fun `test symbol management`() {
        val apiClient = TestApiClient()

        val config = apiClient.getConfiguration()
        val chains = config.evmChains
        assertEquals(chains.size, 2)
        val chainId = chains[0].id
        val contractAddress = Address("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")
        val symbolName = "NAME:$chainId"
        val adminRequest = AdminRoutes.Companion.AdminSymbol(
            chainId = chainId,
            name = symbolName,
            description = "Description",
            contractAddress = contractAddress,
            decimals = 18u,
            iconUrl = "icon.svg",
            withdrawalFee = BigInteger.valueOf(100L),
            addToWallets = true,
        )
        apiClient.tryCreateSymbol(adminRequest).assertError(ApiError(ReasonCode.AuthenticationError, "Access denied"))
        transaction {
            WalletEntity.getOrCreate(apiClient.address).isAdmin = true
        }
        try {
            apiClient.createSymbol(adminRequest)
            await.atMost(sequencerResponseTimeout).until {
                transaction { SymbolEntity.forName(symbolName) }.withdrawalFee == BigInteger.valueOf(100)
            }
            val dbSymbol = transaction {
                val symbol = SymbolEntity.forName(symbolName)
                assertEquals(symbol.decimals, 18.toUByte())
                assertEquals(symbol.iconUrl, "icon.svg")
                assertEquals(symbol.addToWallets, true)
                assertEquals(symbol.contractAddress, contractAddress)
                assertEquals(symbol.withdrawalFee, BigInteger.valueOf(100))
                assertEquals(symbol.faucetSupported(FaucetMode.AllSymbols), true)
                assertEquals(symbol.description, "Description")
                symbol
            }
            apiClient.listSymbols().first { it.name == symbolName }.let { symbol ->
                assertEquals(symbol.decimals, dbSymbol.decimals)
                assertEquals(symbol.iconUrl, dbSymbol.iconUrl)
                assertEquals(symbol.addToWallets, dbSymbol.addToWallets)
                assertEquals(symbol.contractAddress, dbSymbol.contractAddress)
                assertEquals(symbol.withdrawalFee, dbSymbol.withdrawalFee)
                assertEquals(symbol.description, dbSymbol.description)
            }
            apiClient.patchSymbol(
                adminRequest.copy(
                    name = symbolName,
                    description = "Changed description",
                    addToWallets = false,
                    iconUrl = "changed.svg",
                    withdrawalFee = BigInteger.ONE,
                ),
            )
            await.atMost(sequencerResponseTimeout).until {
                transaction {
                    dbSymbol.refresh()
                }
                dbSymbol.withdrawalFee == BigInteger.ONE
            }
            assertEquals(dbSymbol.decimals, 18.toUByte())
            assertEquals(dbSymbol.iconUrl, "changed.svg")
            assertEquals(dbSymbol.addToWallets, false)
            assertEquals(dbSymbol.contractAddress, contractAddress)
            assertEquals(dbSymbol.withdrawalFee, BigInteger.valueOf(1))
            assertEquals(dbSymbol.description, "Changed description")
        } finally {
            transaction {
                SymbolEntity.findById(SymbolId(chainId, "NAME"))?.delete()
                WalletEntity.getOrCreate(apiClient.address).isAdmin = false
            }
        }
    }

    @Test
    fun `test market management`() {
        val apiClient = TestApiClient()
        transaction {
            WalletEntity.getOrCreate(apiClient.address).isAdmin = true
        }

        val config = apiClient.getConfiguration()
        val chains = config.evmChains
        assertEquals(chains.size, 2)
        val chainId1 = chains[0].id
        val chainId2 = chains[1].id
        val symbolName1 = "NAME:$chainId1"
        val symbolName2 = "NAME:$chainId2"
        val marketId = MarketId("$symbolName1/$symbolName2")
        try {
            apiClient.createSymbol(
                AdminRoutes.Companion.AdminSymbol(
                    chainId = chainId1,
                    name = symbolName1,
                    description = "Description 1",
                    contractAddress = null,
                    decimals = 18u,
                    iconUrl = "",
                    withdrawalFee = BigInteger.ZERO,
                    addToWallets = false,
                ),
            )
            apiClient.createSymbol(
                AdminRoutes.Companion.AdminSymbol(
                    chainId = chainId2,
                    name = symbolName2,
                    description = "Description 2",
                    contractAddress = null,
                    decimals = 18u,
                    iconUrl = "",
                    withdrawalFee = BigInteger.ZERO,
                    addToWallets = false,
                ),
            )
            await.atMost(sequencerResponseTimeout).until {
                apiClient.listSymbols().firstOrNull { it.name == symbolName2 } != null
            }

            apiClient.createMarket(
                AdminRoutes.Companion.AdminMarket(
                    id = marketId,
                    tickSize = "0.1".toBigDecimal(),
                    lastPrice = "10.01".toBigDecimal(),
                    minFee = BigInteger.TEN,
                ),
            )
            await.atMost(sequencerResponseTimeout).until {
                transaction { MarketEntity.findById(marketId) }?.minFee == BigInteger.TEN
            }
            val dbMarket = transaction {
                val market = MarketEntity.findById(marketId)!!
                assertEquals(market.baseSymbol.name, symbolName1)
                assertEquals(market.quoteSymbol.name, symbolName2)
                assertEquals(market.tickSize, "0.1".toBigDecimal().setScale(18))
                assertEquals(market.lastPrice, "10.01".toBigDecimal().setScale(18))
                assertEquals(market.minFee, BigInteger.TEN)
                market
            }
            apiClient.listMarkets().first { it.id == marketId }.let { market ->
                assertEquals(market.minFee, dbMarket.minFee)
                assertEquals(market.tickSize, dbMarket.tickSize)
                assertEquals(market.lastPrice, dbMarket.lastPrice)
            }
            apiClient.patchMarket(
                AdminRoutes.Companion.AdminMarket(
                    id = marketId,
                    tickSize = "0.1".toBigDecimal(),
                    lastPrice = "10.01".toBigDecimal(),
                    minFee = BigInteger.ONE,
                ),
            )
            await.atMost(sequencerResponseTimeout).until {
                transaction { MarketEntity.findById(marketId)!! }.minFee == BigInteger.ONE
            }
        } finally {
            transaction {
                MarketEntity.findById(marketId)?.delete()
                SymbolEntity.findById(SymbolId(chainId1, "NAME"))?.delete()
                SymbolEntity.findById(SymbolId(chainId2, "NAME"))?.delete()
                WalletEntity.getOrCreate(apiClient.address).isAdmin = false
            }
        }
    }
}
