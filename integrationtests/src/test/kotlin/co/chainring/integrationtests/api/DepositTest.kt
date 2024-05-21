package co.chainring.integrationtests.api

import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.Deposit
import co.chainring.core.client.ws.blocking
import co.chainring.core.client.ws.subscribeToBalances
import co.chainring.core.model.Symbol
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.waitForBalance
import co.chainring.integrationtests.utils.AssetAmount
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.Wallet
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import co.chainring.tasks.fixtures.toChainSymbol
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import kotlin.test.Test
import kotlin.test.assertNotNull

@ExtendWith(AppUnderTestRunner::class)
class DepositTest {
    @Test
    fun `deposits multiple chains`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        assertEquals(config.chains.size, 2)

        (0 until config.chains.size).forEach { index ->

            wallet.switchChain(config.chains[index].id)
            Faucet.fund(wallet.address, chainId = wallet.currentChainId)

            val btc = config.chains[index].symbols.first { it.name == "BTC".toChainSymbol(index) }
            val usdc = config.chains[index].symbols.first { it.name == "USDC".toChainSymbol(index) }
            val symbolFilterList = listOf(btc.name, usdc.name)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20(usdcMintAmount)

            assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            val btcDepositAmount = AssetAmount(btc, "0.001")
            val usdcDepositAmount = AssetAmount(usdc, "15")

            val btcDepositTxHash = wallet.asyncDeposit(btcDepositAmount)
            assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == btc.name })
            val pendingBtcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDepositAmount.inFundamentalUnits, btcDepositTxHash)).deposit
            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

            assertEquals(listOf(pendingBtcDeposit), apiClient.listDeposits().deposits.filter { it.symbol.value == btc.name })

            wallet.currentBlockchainClient().mine()

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                ),
            )

            val btcDeposit = apiClient.getDeposit(pendingBtcDeposit.id).deposit
            assertEquals(Deposit.Status.Complete, btcDeposit.status)
            assertEquals(listOf(btcDeposit), apiClient.listDeposits().deposits.filter { it.symbol.value == btc.name })

            val depositGasCost = wallet.currentBlockchainClient().getTransactionReceipt(btcDepositTxHash).let { receipt ->
                assertNotNull(receipt)
                AssetAmount(btc, receipt.gasUsed * Numeric.decodeQuantity(receipt.effectiveGasPrice))
            }
            assertEquals(wallet.getWalletBalance(btc), walletStartingBtcBalance - btcDepositAmount - depositGasCost)

            // deposit some USDC
            val usdcDepositTxHash = wallet.asyncDeposit(usdcDepositAmount)

            assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == usdc.name })
            val pendingUsdcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(usdc.name), usdcDepositAmount.inFundamentalUnits, usdcDepositTxHash)).deposit
            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

            assertEquals(listOf(pendingUsdcDeposit, btcDeposit), apiClient.listDeposits().deposits.filter { symbolFilterList.contains(it.symbol.value) })

            wallet.currentBlockchainClient().mine()

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )

            val usdcDeposit = apiClient.getDeposit(pendingUsdcDeposit.id).deposit
            assertEquals(Deposit.Status.Complete, usdcDeposit.status)

            assertEquals(listOf(usdcDeposit, btcDeposit), apiClient.listDeposits().deposits.filter { symbolFilterList.contains(it.symbol.value) })

            assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount - usdcDepositAmount)
        }
    }
}
