package xyz.funkybit.integrationtests.api

import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.FaucetApiRequest
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import java.math.BigInteger
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotEquals

@ExtendWith(AppUnderTestRunner::class)
class FaucetTest {
    @Test
    fun `receive native tokens using faucet`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val config = apiClient.getConfiguration()

        val chains = config.evmChains
        assertEquals(chains.size, 2)

        chains.forEach { chain ->
            wallet.switchChain(chain.id)
            val nativeSymbol = Symbol(chain.symbols.first { it.contractAddress == null }.name)

            val nativeBalanceBefore = wallet.getWalletNativeBalance()
            apiClient.faucet(FaucetApiRequest(nativeSymbol, wallet.address)).also {
                assertEquals(nativeSymbol, it.symbol)
                assertEquals(BigInteger("1000000000000000000"), it.amount)
                assertEquals(chain.id, it.chainId)
                assertNotEquals(TxHash.emptyHash(), it.txHash)
            }

            await
                .withAlias("Waiting for block confirmation")
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofMillis(10000L))
                .untilAsserted {
                    wallet.currentBlockchainClient().mine()
                    val nativeBalanceAfter = wallet.getWalletNativeBalance()
                    assertEquals(BigInteger("1000000000000000000"), nativeBalanceAfter - nativeBalanceBefore)
                }
        }
    }

    @Test
    fun `receive ERC20 tokens using faucet`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        val config = apiClient.getConfiguration()

        val chains = config.evmChains
        assertEquals(chains.size, 2)

        chains.forEach { chain ->
            wallet.switchChain(chain.id)
            val erc20Symbol = Symbol(chain.symbols.first { it.contractAddress != null && it.name.startsWith("ETH") }.name)

            val balanceBefore = wallet.getWalletERC20Balance(erc20Symbol)
            apiClient.faucet(FaucetApiRequest(erc20Symbol, wallet.address)).also {
                assertEquals(erc20Symbol, it.symbol)
                assertEquals(BigInteger("1000000000000000000"), it.amount)
                assertEquals(chain.id, it.chainId)
                assertNotEquals(TxHash.emptyHash(), it.txHash)
            }

            await
                .withAlias("Waiting for block confirmation")
                .pollInSameThread()
                .pollDelay(Duration.ofMillis(100))
                .pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofMillis(10000L))
                .untilAsserted {
                    wallet.currentBlockchainClient().mine()
                    val balanceAfter = wallet.getWalletERC20Balance(erc20Symbol)
                    assertEquals(BigInteger("1000000000000000000"), balanceAfter - balanceBefore)
                }
        }
    }
}
