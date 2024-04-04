package co.chainring.integrationtests.exchange

import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHexBytes
import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.testutils.Wallet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.ECKeyPair
import org.web3j.utils.Numeric
import java.math.BigDecimal
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class DepositAndWithdrawalTest {

    private val walletPrivateKeyHex = "0xdbda1821b80551c9d65939329250298aa3472ba22feea921c0cf5d620ea67b97"

    @Test
    fun testConfiguration() {
        val apiClient = ApiClient()
        val config = apiClient.getConfiguration().chains.first()
        assertEquals(config.contracts.size, 1)
        assertEquals(config.contracts[0].name, ContractType.Exchange.name)
        val client = BlockchainClient().loadExchangeContract(config.contracts[0].address)
        assertEquals(client.version.send().toInt(), 1)

        assertNotNull(config.symbols.firstOrNull { it.name == "ETH" })
        assertNotNull(config.symbols.firstOrNull { it.name == "USDC" })

        val nativeToken = config.symbols.first { it.contractAddress == null }
        assertEquals("BTC", nativeToken.name)
        assertEquals(18.toUByte(), nativeToken.decimals)
    }

    @Test
    fun testERC20DepositsAndWithdrawals() {
        val apiClient = ApiClient(ECKeyPair.create(walletPrivateKeyHex.toHexBytes()))
        val wallet = Wallet(apiClient)
        val decimals = wallet.symbols.first { it.name == "USDC" }.decimals.toInt()

        // mint some USDC
        val startingUsdcWalletBalance = wallet.getWalletERC20Balance("USDC")
        val mintAmount = BigDecimal("20").toFundamentalUnits(decimals)
        wallet.mintERC20("USDC", mintAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount)

        val startingUsdcExchangeBalance = wallet.getExchangeERC20Balance("USDC")
        val depositAmount = BigDecimal("15").toFundamentalUnits(decimals)

        wallet.depositERC20("USDC", depositAmount)
        assertEquals(wallet.getExchangeERC20Balance("USDC"), startingUsdcExchangeBalance + depositAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount - depositAmount)

        val withdrawalAmount = BigDecimal("12").toFundamentalUnits(decimals)
        wallet.withdrawERC20("USDC", withdrawalAmount)
        assertEquals(wallet.getExchangeERC20Balance("USDC"), startingUsdcExchangeBalance + depositAmount - withdrawalAmount)
        assertEquals(wallet.getWalletERC20Balance("USDC"), startingUsdcWalletBalance + mintAmount - depositAmount + withdrawalAmount)
    }

    @Test
    fun testNativeDepositsAndWithdrawals() {
        val apiClient = ApiClient(ECKeyPair.create(walletPrivateKeyHex.toHexBytes()))
        val wallet = Wallet(apiClient)
        val decimals = wallet.symbols.first { it.contractAddress == null }.decimals.toInt()

        val startingWalletBalance = wallet.getWalletNativeBalance()
        val startingExchangeBalance = wallet.getExchangeNativeBalance()
        val depositAmount = BigDecimal("2").toFundamentalUnits(decimals)

        val depositTxReceipt = wallet.depositNative(depositAmount)
        val depositGasCost = depositTxReceipt.gasUsed * Numeric.decodeQuantity(depositTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getExchangeNativeBalance(), startingExchangeBalance + depositAmount)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount - depositGasCost)

        val withdrawalAmount = BigDecimal("2").toFundamentalUnits(decimals)
        val withdrawalTxReceipt = wallet.withdrawNative(withdrawalAmount)
        val withdrawalGasCost = withdrawalTxReceipt.gasUsed * Numeric.decodeQuantity(withdrawalTxReceipt.effectiveGasPrice)
        assertEquals(wallet.getExchangeNativeBalance(), startingExchangeBalance + depositAmount - withdrawalAmount)
        assertEquals(wallet.getWalletNativeBalance(), startingWalletBalance - depositAmount + withdrawalAmount - depositGasCost - withdrawalGasCost)
    }
}
