package xyz.funkybit.integrationtests.api

import kotlinx.datetime.Clock
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.DepositEntity
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
import xyz.funkybit.tasks.fixtures.toChainSymbol
import java.lang.System.getenv
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes

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

        val chains = config.evmChains
        assertEquals(chains.size, 2)

        (0 until chains.size).forEach { index ->

            wallet.switchChain(config.chains[index].id)
            Faucet.fundAndMine(wallet.address, chainId = wallet.currentChainId)

            val btc = config.chains[index].symbols.first { it.name == "BTC".toChainSymbol(config.chains[index].id) }
            val usdc = config.chains[index].symbols.first { it.name == "USDC".toChainSymbol(config.chains[index].id) }
            val symbolFilterList = listOf(btc.name, usdc.name)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20AndMine(usdcMintAmount)

            assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            val btcDepositAmount = AssetAmount(btc, "0.001")
            val usdcDepositAmount = AssetAmount(usdc, "15")

            val btcDepositTxHash = wallet.sendDepositTx(btcDepositAmount)
            assertTrue(apiClient.listDeposits().deposits.none { it.symbol.value == btc.name })
            val pendingBtcDeposit = apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDepositAmount.inFundamentalUnits, btcDepositTxHash)).deposit
            assertEquals(Deposit.Status.Pending, pendingBtcDeposit.status)

            assertEquals(listOf(pendingBtcDeposit), apiClient.listDeposits().deposits.filter { it.symbol.value == btc.name })

            // test idempotence
            assertEquals(pendingBtcDeposit, apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDepositAmount.inFundamentalUnits, btcDepositTxHash)).deposit)
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
            val usdcDepositTxHash = wallet.sendDepositTx(usdcDepositAmount)

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

    @Test
    fun `test deposits are scoped to wallet`() {
        val apiClient1 = TestApiClient()
        val wallet1 = Wallet(apiClient1)

        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)

        Faucet.fundAndMine(wallet1.address, chainId = wallet1.currentChainId)
        Faucet.fundAndMine(wallet2.address, chainId = wallet2.currentChainId)

        val chain1 = apiClient1.getConfiguration().chains[0]
        val btc = chain1.symbols.first { it.name == "BTC:${chain1.id}" }

        val btcDeposit1Amount = AssetAmount(btc, "0.01")
        val btcDeposit2Amount = AssetAmount(btc, "0.02")

        val btcDeposit1TxHash = wallet1.sendNativeDepositTx(btcDeposit1Amount.inFundamentalUnits)
        val pendingBtcDeposit1 = apiClient1.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDeposit1Amount.inFundamentalUnits, btcDeposit1TxHash)).deposit
        val btcDeposit2TxHash = wallet2.sendNativeDepositTx(btcDeposit2Amount.inFundamentalUnits)
        val pendingBtcDeposit2 = apiClient2.createDeposit(CreateDepositApiRequest(Symbol(btc.name), btcDeposit1Amount.inFundamentalUnits, btcDeposit2TxHash)).deposit
        assertEquals(listOf(pendingBtcDeposit1), apiClient1.listDeposits().deposits.filter { it.symbol.value == btc.name })
        assertEquals(listOf(pendingBtcDeposit2), apiClient2.listDeposits().deposits.filter { it.symbol.value == btc.name })
    }

    @Test
    fun `test on chain deposit detection`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        Faucet.fundAndMine(wallet.address, chainId = wallet.currentChainId)

        val chain = apiClient.getConfiguration().chains[0]
        val btc = chain.symbols.first { it.name == "BTC:${chain.id}" }
        val depositAmount = AssetAmount(btc, "0.01")

        val depositTxHash = TxHash(wallet.depositAndMine(depositAmount).transactionHash)

        await
            .withAlias("Waiting for deposit to be detected")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == depositTxHash && it.amount == depositAmount.inFundamentalUnits
                } != null
            }
    }

    @Test
    fun `test on chain deposit detection - forks are handled`() {
        // test is skipped in the test env
        Assumptions.assumeTrue((getenv("INTEGRATION_RUN") ?: "0") != "1")

        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        Faucet.fundAndMine(wallet.address, chainId = wallet.currentChainId)

        val chain = apiClient.getConfiguration().chains[0]
        val btc = chain.symbols.first { it.name == "BTC:${chain.id}" }

        val chainClient = Faucet.blockchainClient(chain.id)
        val snapshotId = chainClient.snapshot().id

        val deposit1TxHash = wallet.sendNativeDepositTx(AssetAmount(btc, "0.01").inFundamentalUnits)
        chainClient.mine()

        await
            .withAlias("Waiting for deposit 1 to be detected")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == deposit1TxHash
                } != null
            }

        chainClient.revert(snapshotId)
        chainClient.mine()

        val deposit2TxHash = wallet.sendNativeDepositTx(AssetAmount(btc, "0.02").inFundamentalUnits)
        chainClient.mine()

        await
            .withAlias("Waiting for deposit 2 to be completed")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == deposit2TxHash && it.status == Deposit.Status.Complete
                } != null
            }

        // check that deposit 1 was marked as failed
        apiClient.listDeposits().deposits.first { it.txHash == deposit1TxHash }.also {
            assertEquals(Deposit.Status.Failed, it.status)
            assertEquals("Fork rollback", it.error)
        }
    }

    @Test
    fun `test deposit fails after timeout if no tx receipt found`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)

        Faucet.fundAndMine(wallet.address, chainId = wallet.currentChainId)

        val chain = apiClient.getConfiguration().chains[0]
        val btc = chain.symbols.first { it.name == "BTC:${chain.id}" }
        val amount = AssetAmount(btc, "0.01")

        val chainClient = Faucet.blockchainClient(chain.id)

        val depositTxHash = wallet.sendNativeDepositTx(amount.inFundamentalUnits)
        apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), amount.inFundamentalUnits, depositTxHash)).deposit.also {
            assertEquals(Deposit.Status.Pending, it.status)
        }
        chainClient.dropTransaction(depositTxHash)

        transaction {
            DepositEntity.findByTxHash(depositTxHash)!!.also {
                it.createdAt = Clock.System.now() - 11.minutes
            }
        }

        await
            .withAlias("Waiting for deposit to be marked as failed")
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(10000L))
            .until {
                apiClient.listDeposits().deposits.firstOrNull {
                    it.txHash == depositTxHash && it.status == Deposit.Status.Failed
                } != null
            }

        assertEquals(depositTxHash, wallet.sendNativeDepositTx(amount.inFundamentalUnits))

        // verify that same deposit tx can be submitted again
        apiClient.createDeposit(CreateDepositApiRequest(Symbol(btc.name), amount.inFundamentalUnits, depositTxHash)).deposit.also {
            assertEquals(Deposit.Status.Pending, it.status)
        }
    }
}
