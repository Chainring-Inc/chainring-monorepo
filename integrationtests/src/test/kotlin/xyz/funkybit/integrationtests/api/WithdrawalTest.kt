package xyz.funkybit.integrationtests.api

import org.http4k.client.WebsocketClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.utils.Numeric
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.websocket.SubscriptionTopic
import xyz.funkybit.core.evm.EIP712Transaction
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.waitForBalance
import xyz.funkybit.integrationtests.testutils.waitForFinalizedWithdrawal
import xyz.funkybit.integrationtests.testutils.waitForFinalizedWithdrawalWithForking
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.Faucet
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.Wallet
import xyz.funkybit.integrationtests.utils.assertBalances
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
import xyz.funkybit.integrationtests.utils.assertError
import xyz.funkybit.integrationtests.utils.assertMessagesReceived
import xyz.funkybit.integrationtests.utils.blocking
import xyz.funkybit.integrationtests.utils.subscribeToBalances
import xyz.funkybit.tasks.fixtures.toChainSymbol
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class WithdrawalTest {
    @Test
    fun `withdrawals multiple chains`() {
        val apiClient = TestApiClient()
        val wallet = Wallet(apiClient)
        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val config = apiClient.getConfiguration()
        val chains = config.evmChains
        assertEquals(chains.size, 2)

        (0 until chains.size).forEach { index ->

            wallet.switchChain(chains[index].id)
            Faucet.fundAndMine(wallet.address, chainId = wallet.currentChainId)

            val btc = chains[index].symbols.first { it.name == "BTC".toChainSymbol(chains[index].id) }
            val usdc = chains[index].symbols.first { it.name == "USDC".toChainSymbol(chains[index].id) }
            assertEquals(BigDecimal("0.00002").toFundamentalUnits(btc.decimals.toInt()), btc.withdrawalFee)
            assertEquals(BigDecimal("1").toFundamentalUnits(usdc.decimals.toInt()), usdc.withdrawalFee)
            val symbolFilterList = listOf(btc.name, usdc.name)

            // mint some USDC
            val usdcMintAmount = AssetAmount(usdc, "20")
            wallet.mintERC20AndMine(usdcMintAmount)

            assertEquals(wallet.getWalletBalance(usdc), usdcMintAmount)

            val walletStartingBtcBalance = wallet.getWalletBalance(btc)

            // deposit some BTC
            val btcDepositAmount = AssetAmount(btc, "0.001")
            val depositTxReceipt = wallet.depositAndMine(btcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount),
                ),
            )

            val depositGasCost = AssetAmount(btc, depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice))
            assertEquals(walletStartingBtcBalance - btcDepositAmount - depositGasCost, wallet.getWalletBalance(btc))

            // deposit more BTC
            val depositTxReceipt2 = wallet.depositAndMine(btcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2")),
                ),
            )

            val depositGasCost2 = AssetAmount(btc, depositTxReceipt2.gasUsed * Numeric.decodeQuantity(depositTxReceipt2.effectiveGasPrice))

            assertEquals(
                walletStartingBtcBalance - (btcDepositAmount * BigDecimal("2")) - depositGasCost - depositGasCost2,
                wallet.getWalletBalance(btc),
            )

            // deposit some USDC
            val usdcDepositAmount = AssetAmount(usdc, "16")
            wallet.depositAndMine(usdcDepositAmount)
            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2")),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )
            assertEquals(usdcMintAmount - usdcDepositAmount, wallet.getWalletBalance(usdc))

            val walletBtcBalanceBeforeWithdrawals = wallet.getWalletBalance(btc)

            // withdraw some BTC
            val btcWithdrawalAmount = AssetAmount(btc, "0.001")

            val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits)).withdrawal
            assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)
            assertEquals(
                listOf(pendingBtcWithdrawal.id),
                apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) }.map { it.id },
            )

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(
                        btc,
                        total = btcDepositAmount * BigDecimal("2"),
                        available = btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount,
                    ),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )

            waitForFinalizedWithdrawalWithForking(pendingBtcWithdrawal.id)

            val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
            assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)
            assertEquals(
                listOf(btcWithdrawal.id),
                apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) }.map { it.id },
            )
            assertEquals(btc.withdrawalFee, btcWithdrawal.fee)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(usdcDepositAmount),
                ),
            )
            assertEquals(
                walletBtcBalanceBeforeWithdrawals + btcWithdrawalAmount - AssetAmount(btc, btcWithdrawal.fee),
                wallet.getWalletBalance(btc),
            )

            // withdraw some USDC
            val usdcWithdrawalAmount = AssetAmount(usdc, "14")

            val pendingUsdcWithdrawal =
                apiClient.createWithdrawal(wallet.signWithdraw(usdc.name, usdcWithdrawalAmount.inFundamentalUnits)).withdrawal
            assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal.status)
            assertEquals(
                listOf(pendingUsdcWithdrawal.id, btcWithdrawal.id),
                apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) }.map { it.id },
            )

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(
                        usdc,
                        total = usdcDepositAmount,
                        available = usdcDepositAmount - usdcWithdrawalAmount,
                    ),
                ),
            )

            waitForFinalizedWithdrawal(pendingUsdcWithdrawal.id)

            val usdcWithdrawal = apiClient.getWithdrawal(pendingUsdcWithdrawal.id).withdrawal
            assertEquals(WithdrawalStatus.Complete, usdcWithdrawal.status)
            assertEquals(listOf(usdcWithdrawal.id, btcWithdrawal.id), apiClient.listWithdrawals().withdrawals.filter { symbolFilterList.contains(it.symbol.value) }.map { it.id })
            assertEquals(usdc.withdrawalFee, usdcWithdrawal.fee)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(usdcDepositAmount - usdcWithdrawalAmount),
                ),
            )
            assertEquals(
                usdcMintAmount - usdcDepositAmount + usdcWithdrawalAmount - AssetAmount(usdc, usdcWithdrawal.fee),
                wallet.getWalletBalance(usdc),
            )

            val remainingUsdcBalance = usdcDepositAmount - usdcWithdrawalAmount

            // when requested withdrawal amount > remaining amount, should fail with Insufficient balance
            val pendingUsdcWithdrawal2 = apiClient.createWithdrawal(
                wallet.signWithdraw(
                    usdc.name,
                    remainingUsdcBalance.inFundamentalUnits + BigInteger.ONE,
                ),
            ).withdrawal
            assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal2.status)
            waitForFinalizedWithdrawal(pendingUsdcWithdrawal2.id)
            val usdcWithdrawal2 = apiClient.getWithdrawal(pendingUsdcWithdrawal2.id).withdrawal
            assertEquals(WithdrawalStatus.Failed, usdcWithdrawal2.status)
            assertEquals("Insufficient Balance", usdcWithdrawal2.error)

            // when requested withdrawal amount is 0, everything should be withdrawn
            val pendingUsdcWithdrawal3 = apiClient.createWithdrawal(
                wallet.signWithdraw(
                    usdc.name,
                    BigInteger.ZERO,
                ),
            ).withdrawal
            assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal3.status)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(
                        usdc,
                        total = remainingUsdcBalance,
                        available = AssetAmount(usdc, "0"),
                    ),
                ),
            )

            waitForFinalizedWithdrawalWithForking(pendingUsdcWithdrawal3.id)

            val usdcWithdrawal3 = apiClient.getWithdrawal(pendingUsdcWithdrawal3.id).withdrawal
            assertEquals(WithdrawalStatus.Complete, usdcWithdrawal3.status)
            assertEquals(remainingUsdcBalance.inFundamentalUnits, usdcWithdrawal3.amount)
            assertEquals(usdc.withdrawalFee, usdcWithdrawal3.fee)

            waitForBalance(
                apiClient,
                wsClient,
                listOf(
                    ExpectedBalance(btcDepositAmount * BigDecimal("2") - btcWithdrawalAmount),
                    ExpectedBalance(AssetAmount(usdc, "0")),
                ),
            )

            assertEquals(
                usdcMintAmount - AssetAmount(usdc, usdcWithdrawal.fee) - AssetAmount(usdc, usdcWithdrawal.fee),
                wallet.getWalletBalance(usdc),
            )
        }
    }

    @Test
    fun `withdrawal errors`() {
        val apiClient = TestApiClient()
        val usdc = apiClient.getConfiguration().chains.flatMap { it.symbols }.first { it.name.startsWith("USDC:") }

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fundAndMine(wallet.address)

        val amount = AssetAmount(usdc, "1000")
        wallet.mintERC20AndMine(amount * BigDecimal("2"))

        wallet.depositAndMine(amount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(amount),
            ),
        )

        // invalid signature
        apiClient.tryCreateWithdrawal(
            wallet.signWithdraw(usdc.name, amount.inFundamentalUnits).copy(amount = BigInteger.TWO),
        ).assertError(
            ApiError(ReasonCode.SignatureNotValid, "Invalid signature"),
        )
    }

    @Test
    fun `withdraw all while deposit in progress`() {
        val apiClient = TestApiClient()
        val usdc = apiClient.getConfiguration().chains.flatMap { it.symbols }.first { it.name.startsWith("USDC:") }

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fundAndMine(wallet.address)

        val initialDepositAmount = AssetAmount(usdc, "1001")
        wallet.mintERC20AndMine(initialDepositAmount * BigDecimal("2"))

        wallet.depositAndMine(initialDepositAmount)
        // available/total should match
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(initialDepositAmount),
            ),
        )

        val pendingUsdcWithdrawal =
            apiClient.createWithdrawal(wallet.signWithdraw(usdc.name, BigInteger.ZERO)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal.status)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(initialDepositAmount.symbol, initialDepositAmount.amount, BigDecimal.ZERO),
            ),
        )
        // submit a deposit
        val newDepositAmount = AssetAmount(usdc, "100")
        wallet.depositAndMine(newDepositAmount)

        waitForFinalizedWithdrawal(pendingUsdcWithdrawal.id)
        val usdcWithdrawal = apiClient.getWithdrawal(pendingUsdcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, usdcWithdrawal.status)
        assertEquals(initialDepositAmount.inFundamentalUnits, usdcWithdrawal.amount)

        // wait for 2 balance changes, one for the withdrawal completing on chain
        // and one for the deposit being applied at the sequencer.
        wsClient.apply {
            assertMessagesReceived(2) { messages ->
                assertEquals(messages.map { it.topic }.toSet(), setOf(SubscriptionTopic.Balances))
            }
        }

        // verify the total/available balance is the new deposit amount only.
        assertBalances(listOf(ExpectedBalance(newDepositAmount)), apiClient.getBalances().balances)
    }

    @Test
    fun `withdraw all balance in contract less that amount from sequencer`() {
        val apiClient = TestApiClient()
        val usdc = apiClient.getConfiguration().chains.flatMap { it.symbols }.first { it.name.startsWith("USDC:") }

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fundAndMine(wallet.address)

        val initialDepositAmount = AssetAmount(usdc, "1000")
        wallet.mintERC20AndMine(initialDepositAmount * BigDecimal("2"))

        wallet.depositAndMine(initialDepositAmount)
        // available/total should match
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(initialDepositAmount),
            ),
        )

        val usdcWithdrawalAmount = AssetAmount(usdc, "200")

        val pendingUsdcWithdrawal =
            apiClient.createWithdrawal(wallet.signWithdraw(usdc.name, usdcWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal.status)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(
                    usdc,
                    total = initialDepositAmount,
                    available = initialDepositAmount - usdcWithdrawalAmount,
                ),
            ),
        )

        waitForFinalizedWithdrawal(pendingUsdcWithdrawal.id)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(initialDepositAmount - usdcWithdrawalAmount),
            ),
        )

        transaction {
            WithdrawalEntity[pendingUsdcWithdrawal.id].status = WithdrawalStatus.Sequenced
        }

        waitForFinalizedWithdrawal(pendingUsdcWithdrawal.id)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(
                    usdc,
                    initialDepositAmount.amount - usdcWithdrawalAmount.amount * BigDecimal(2),
                    initialDepositAmount.amount - usdcWithdrawalAmount.amount,
                ),
            ),
        )

        val pendingUsdcWithdrawal2 = apiClient.createWithdrawal(
            wallet.signWithdraw(
                usdc.name,
                BigInteger.ZERO,
            ),
        ).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingUsdcWithdrawal2.status)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(
                    usdc,
                    initialDepositAmount.amount - usdcWithdrawalAmount.amount * BigDecimal(2),
                    BigDecimal.ZERO,
                ),
            ),
        )

        var usdcWithdrawal2 = apiClient.getWithdrawal(pendingUsdcWithdrawal2.id).withdrawal
        assertEquals(AssetAmount(usdc, "800").inFundamentalUnits, usdcWithdrawal2.amount)

        waitForFinalizedWithdrawal(pendingUsdcWithdrawal2.id)

        usdcWithdrawal2 = apiClient.getWithdrawal(pendingUsdcWithdrawal2.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, usdcWithdrawal2.status)
        // the returned amount after on chain tx is finished should be adjusted to what the contract actual transferred
        assertEquals(AssetAmount(usdc, "600").inFundamentalUnits, usdcWithdrawal2.amount)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(AssetAmount(usdc, "0")),
            ),
        )
    }

    @Test
    fun `test withdrawals are scoped to wallet`() {
        val apiClient1 = TestApiClient()
        val wallet1 = Wallet(apiClient1)
        val wsClient1 = WebsocketClient.blocking(apiClient1.authToken)
        wsClient1.subscribeToBalances()
        wsClient1.assertBalancesMessageReceived()

        val apiClient2 = TestApiClient()
        val wallet2 = Wallet(apiClient2)
        val wsClient2 = WebsocketClient.blocking(apiClient2.authToken)
        wsClient2.subscribeToBalances()
        wsClient2.assertBalancesMessageReceived()

        Faucet.fundAndMine(wallet1.address, chainId = wallet1.currentChainId)
        Faucet.fundAndMine(wallet2.address, chainId = wallet2.currentChainId)
        val btc = apiClient1.getConfiguration().chains.flatMap { it.symbols }.first { it.name.startsWith("BTC:") }

        val btcDeposit1Amount = AssetAmount(btc, "0.01")
        val btcDeposit2Amount = AssetAmount(btc, "0.02")

        wallet1.depositAndMine(btcDeposit1Amount)
        wallet2.depositAndMine(btcDeposit2Amount)

        waitForBalance(
            apiClient1,
            wsClient1,
            listOf(
                ExpectedBalance(btc, total = btcDeposit1Amount, available = btcDeposit1Amount),
            ),
        )
        waitForBalance(
            apiClient2,
            wsClient2,
            listOf(
                ExpectedBalance(btc, total = btcDeposit2Amount, available = btcDeposit2Amount),
            ),
        )
        val btcWithdrawal1Amount = AssetAmount(btc, "0.001")
        val btcWithdrawal2Amount = AssetAmount(btc, "0.002")

        val pendingBtcWithdrawal1 =
            apiClient1.createWithdrawal(wallet1.signWithdraw(btc.name, btcWithdrawal1Amount.inFundamentalUnits)).withdrawal
        val pendingBtcWithdrawal2 =
            apiClient2.createWithdrawal(wallet2.signWithdraw(btc.name, btcWithdrawal2Amount.inFundamentalUnits)).withdrawal
        assertEquals(listOf(pendingBtcWithdrawal1.id), apiClient1.listWithdrawals().withdrawals.filter { it.symbol.value == btc.name }.map { it.id })
        assertEquals(listOf(pendingBtcWithdrawal2.id), apiClient2.listWithdrawals().withdrawals.filter { it.symbol.value == btc.name }.map { it.id })
    }

    @Test
    fun `withdrawal blockchain failure`() {
        val apiClient = TestApiClient()
        val btc = apiClient.getConfiguration().chains.flatMap { it.symbols }.first { it.name.startsWith("BTC") }

        val wsClient = WebsocketClient.blocking(apiClient.authToken)
        wsClient.subscribeToBalances()
        wsClient.assertBalancesMessageReceived()

        val wallet = Wallet(apiClient)
        Faucet.fundAndMine(wallet.address)

        // deposit some BTC
        val btcDepositAmount = AssetAmount(btc, "0.002")
        wallet.depositAndMine(btcDepositAmount)
        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btcDepositAmount),
            ),
        )

        // withdraw some BTC
        val btcWithdrawalAmount = AssetAmount(btc, "0.001")

        val pendingBtcWithdrawal = apiClient.createWithdrawal(wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits)).withdrawal
        assertEquals(WithdrawalStatus.Pending, pendingBtcWithdrawal.status)
        assertEquals(listOf(pendingBtcWithdrawal.id), apiClient.listWithdrawals().withdrawals.map { it.id })

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btc, total = btcDepositAmount, available = btcDepositAmount - btcWithdrawalAmount),
            ),
        )

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Complete, btcWithdrawal.status)
        assertEquals(listOf(btcWithdrawal.id), apiClient.listWithdrawals().withdrawals.map { it.id })

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btcDepositAmount - btcWithdrawalAmount),
            ),
        )

        // the only reason withdrawals should fail is a signature failure (which should not happen since
        // Exchange API service verifies signatures before sending on chain). To simulate this
        // and verify the sequencer rollbacks, we will modify the withdrawal signature of an already submitted tx and
        // resubmit it on chain - this should fail and trigger the rollback in the sequencer and
        // available balance should go back to what it was before.
        transaction {
            val withdrawal = WithdrawalEntity[pendingBtcWithdrawal.id]
            withdrawal.status = WithdrawalStatus.Sequenced
            // resign with a different nonce so signature should fail
            val signature = wallet.signWithdraw(btc.name, btcWithdrawalAmount.inFundamentalUnits).signature
            withdrawal.transactionData = (withdrawal.transactionData!! as EIP712Transaction.WithdrawTx).copy(
                signature = signature,
            )
        }

        waitForFinalizedWithdrawal(pendingBtcWithdrawal.id)

        val btcWithdrawal2 = apiClient.getWithdrawal(pendingBtcWithdrawal.id).withdrawal
        assertEquals(WithdrawalStatus.Failed, btcWithdrawal2.status)
        assertEquals("Invalid Signature", btcWithdrawal2.error)

        waitForBalance(
            apiClient,
            wsClient,
            listOf(
                ExpectedBalance(btc, total = btcDepositAmount - btcWithdrawalAmount, available = btcDepositAmount),
            ),
        )
    }
}
