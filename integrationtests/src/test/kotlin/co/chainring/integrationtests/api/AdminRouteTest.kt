package co.chainring.integrationtests.api

import co.chainring.apps.api.AdminRoutes
import co.chainring.apps.api.FaucetMode
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.model.Address
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.SymbolId
import co.chainring.core.model.db.WalletEntity
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.assertError
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigInteger
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class AdminRouteTest {
    @Test
    fun `test symbol management`() {
        val apiClient = TestApiClient()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.size, 2)
        val chainId = config.chains[0].id
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
            transaction {
                dbSymbol.refresh()
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
        assertEquals(config.chains.size, 2)
        val chainId1 = config.chains[0].id
        val chainId2 = config.chains[1].id
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
            apiClient.createMarket(
                AdminRoutes.Companion.AdminMarket(
                    id = marketId,
                    tickSize = "0.1".toBigDecimal(),
                    lastPrice = "10.01".toBigDecimal(),
                    minFee = BigInteger.TEN,
                ),
            )
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
            transaction {
                dbMarket.refresh()
            }
            assertEquals(BigInteger.ONE, dbMarket.minFee)
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