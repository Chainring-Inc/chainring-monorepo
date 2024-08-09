package xyz.funkybit.integrationtests.exchange

import org.awaitility.kotlin.await
import org.http4k.client.WebsocketClient
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.Faucet
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.tasks.blockchainClient
import xyz.funkybit.tasks.fixtures.toChainSymbol
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class SovereignWithdrawalTest {

    @Test
    fun testSovereignWithdrawalSequenced() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        val chains = config.evmChains
        Assertions.assertEquals(chains.size, 2)

        (0 until chains.size).forEach { index ->
            wallet.switchChain(chains[index].id)
            Faucet.fundAndMine(wallet.address, amount = BigDecimal("1").toFundamentalUnits(18), chainId = wallet.currentChainId)

            val btc = chains[index].symbols.first { it.name == "BTC".toChainSymbol(chains[index].id) }
            val usdc = chains[index].symbols.first { it.name == "USDC".toChainSymbol(chains[index].id) }
            Assertions.assertEquals(BigDecimal("0.00002").toFundamentalUnits(btc.decimals.toInt()), btc.withdrawalFee)
            Assertions.assertEquals(BigDecimal("1").toFundamentalUnits(usdc.decimals.toInt()), usdc.withdrawalFee)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20AndMine(usdcMintAmount)

            Assertions.assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            // deposit some BTC
            val btcDepositAmount = AssetAmount(btc, "0.5")
            val depositTxReceipt = wallet.depositAndMine(btcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                ),
            )

            val depositGasCost = AssetAmount(btc, depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice))
            Assertions.assertEquals(walletStartingBtcBalance - btcDepositAmount - depositGasCost, wallet.getWalletBalance(btc))

            // deposit some USDC
            val usdcDepositAmount = AssetAmount(usdc, "16")
            wallet.depositAndMine(usdcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )
            Assertions.assertEquals(usdcMintAmount - usdcDepositAmount, wallet.getWalletBalance(usdc))

            // BTC sovereign withdrawal
            val walletBtcBalanceBeforeWithdrawal = wallet.getWalletBalance(btc).amount.toFundamentalUnits(btc.decimals)
            val btcWithdrawalAmount = BigDecimal("0.1")
            val btcWithdrawalTxHash = wallet.requestSovereignWithdrawalAndMine(btc.name, btcWithdrawalAmount.toFundamentalUnits(btc.decimals))
            val btcWithdrawalGasCost = blockchainClient(wallet.currentChainId).gasUsed(btcWithdrawalTxHash)!!
            waitForBalance(apiClient, wsClient, listOf())

            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(1000))
                .atMost(Duration.ofMillis(3000000L))
                .untilAsserted {
                    Faucet.mine(1)

                    val walletBalanceAfterWithdrawal = wallet.getWalletBalance(btc).amount.toFundamentalUnits(btc.decimals)
                    val withdrawalAmount = btcWithdrawalAmount.toFundamentalUnits(btc.decimals)
                    val withdrawalFee = btc.withdrawalFee

                    // calculate also gas since gas is paid in native symbol
                    assertEquals(
                        expected = walletBtcBalanceBeforeWithdrawal + withdrawalAmount - withdrawalFee - btcWithdrawalGasCost,
                        actual = walletBalanceAfterWithdrawal,
                    )
                }
            waitForBalance(apiClient, wsClient, listOf())

            // USDC sovereign withdrawal
            val walletUsdcBalanceBeforeWithdrawal = wallet.getWalletBalance(usdc).amount.toFundamentalUnits(usdc.decimals)
            val usdcWithdrawalAmount = BigDecimal("10")
            wallet.requestSovereignWithdrawalAndMine(usdc.name, usdcWithdrawalAmount.toFundamentalUnits(usdc.decimals))
            waitForBalance(apiClient, wsClient, listOf())

            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(1000))
                .atMost(Duration.ofMillis(3000000L))
                .untilAsserted {
                    Faucet.mine(1)

                    val walletBalanceAfterWithdrawal = wallet.getWalletBalance(usdc).amount.toFundamentalUnits(usdc.decimals)
                    val withdrawalAmount = usdcWithdrawalAmount.toFundamentalUnits(usdc.decimals)
                    val withdrawalFee = usdc.withdrawalFee

                    assertEquals(
                        expected = walletUsdcBalanceBeforeWithdrawal + withdrawalAmount - withdrawalFee,
                        actual = walletBalanceAfterWithdrawal,
                    )
                }
            waitForBalance(apiClient, wsClient, listOf())
        }
    }

    @Test
    fun testSovereignWithdrawalAllSequenced() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        val chains = config.evmChains
        Assertions.assertEquals(chains.size, 2)

        (0 until chains.size).forEach { index ->
            wallet.switchChain(chains[index].id)
            Faucet.fundAndMine(wallet.address, amount = BigDecimal("1").toFundamentalUnits(18), chainId = wallet.currentChainId)

            val btc = chains[index].symbols.first { it.name == "BTC".toChainSymbol(chains[index].id) }
            val usdc = chains[index].symbols.first { it.name == "USDC".toChainSymbol(chains[index].id) }
            Assertions.assertEquals(BigDecimal("0.00002").toFundamentalUnits(btc.decimals.toInt()), btc.withdrawalFee)
            Assertions.assertEquals(BigDecimal("1").toFundamentalUnits(usdc.decimals.toInt()), usdc.withdrawalFee)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20AndMine(usdcMintAmount)

            Assertions.assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            // deposit some BTC
            val btcDepositAmount = AssetAmount(btc, "0.5")
            val depositTxReceipt = wallet.depositAndMine(btcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                ),
            )

            val depositGasCost = AssetAmount(btc, depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice))
            Assertions.assertEquals(walletStartingBtcBalance - btcDepositAmount - depositGasCost, wallet.getWalletBalance(btc))

            // deposit some USDC
            val usdcDepositAmount = AssetAmount(usdc, "16")
            wallet.depositAndMine(usdcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )
            Assertions.assertEquals(usdcMintAmount - usdcDepositAmount, wallet.getWalletBalance(usdc))

            // BTC sovereign withdrawal
            val walletBtcBalanceBeforeWithdrawal = wallet.getWalletBalance(btc).amount.toFundamentalUnits(btc.decimals)
            val exchangeBtcBalanceBeforeWithdrawal = wallet.getExchangeNativeBalance()
            val btcWithdrawalTxHash = wallet.requestSovereignWithdrawalAndMine(btc.name, BigInteger.ZERO)
            val btcWithdrawalGasCost = blockchainClient(wallet.currentChainId).gasUsed(btcWithdrawalTxHash)!!
            waitForBalance(apiClient, wsClient, listOf())

            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(1000))
                .atMost(Duration.ofMillis(3000000L))
                .untilAsserted {
                    Faucet.mine(1)

                    val walletBalanceAfterWithdrawal = wallet.getWalletBalance(btc).amount.toFundamentalUnits(btc.decimals)
                    val withdrawalFee = btc.withdrawalFee

                    // calculate also gas since gas is paid in native symbol
                    assertEquals(
                        expected = walletBtcBalanceBeforeWithdrawal + exchangeBtcBalanceBeforeWithdrawal - withdrawalFee - btcWithdrawalGasCost,
                        actual = walletBalanceAfterWithdrawal,
                    )
                }
            waitForBalance(apiClient, wsClient, listOf())

            // USDC sovereign withdrawal
            val walletUsdcBalanceBeforeWithdrawal = wallet.getWalletBalance(usdc).amount.toFundamentalUnits(usdc.decimals)
            val exchangeUsdcBalanceBeforeWithdrawal = wallet.getExchangeERC20Balance(usdc.name)
            wallet.requestSovereignWithdrawalAndMine(usdc.name, BigInteger.ZERO)
            waitForBalance(apiClient, wsClient, listOf())

            await
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(1000))
                .pollInterval(Duration.ofMillis(1000))
                .atMost(Duration.ofMillis(3000000L))
                .untilAsserted {
                    Faucet.mine(1)

                    val walletBalanceAfterWithdrawal = wallet.getWalletBalance(usdc).amount.toFundamentalUnits(usdc.decimals)
                    val withdrawalFee = usdc.withdrawalFee

                    assertEquals(
                        expected = walletUsdcBalanceBeforeWithdrawal + exchangeUsdcBalanceBeforeWithdrawal - withdrawalFee,
                        actual = walletBalanceAfterWithdrawal,
                    )
                }
            waitForBalance(apiClient, wsClient, listOf())
        }
    }
}
