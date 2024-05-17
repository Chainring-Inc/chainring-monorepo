package co.chainring.integrationtests.utils

import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.Chain
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.SymbolInfo
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.contracts.generated.Exchange
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.blockchain.ContractType
import co.chainring.core.client.rest.ApiClient
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.evm.TokenAddressAndChain
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.Symbol
import co.chainring.core.model.TxHash
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHex
import co.chainring.core.utils.toHexBytes
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger

class Wallet(
    val walletKeypair: ECKeyPair,
    val chains: List<Chain>,
) {

    companion object {
        operator fun invoke(apiClient: ApiClient): Wallet {
            val config = apiClient.getConfiguration().chains
            return Wallet(apiClient.ecKeyPair, config)
        }
    }
    private val blockchainClients = ChainManager.blockchainConfigs.map {
        TestBlockchainClient(ChainManager.getBlockchainClient(it, walletKeypair.privateKey.toByteArray().toHex()).config)
    }

    private val blockchainClientsByChainId = blockchainClients.associateBy { it.chainId }

    var currentChainId: ChainId = blockchainClients.first().chainId

    val address = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(walletKeypair)))

    private val exchangeContractAddressByChainId = chains.associate { it.id to it.contracts.first { it.name == ContractType.Exchange.name }.address }
    private val exchangeContractByChainId = blockchainClients.associate { it.chainId to it.loadExchangeContract(exchangeContractAddressByChainId.getValue(it.chainId)) }

    fun switchChain(chainId: ChainId) {
        currentChainId = chainId
    }

    fun currentBlockchainClient(): TestBlockchainClient {
        return blockchainClientsByChainId.getValue(currentChainId)
    }

    fun getWalletERC20Balance(symbol: String): BigInteger {
        return loadErc20Contract(symbol).balanceOf(address.value).send()
    }

    fun mintERC20(symbol: String, amount: BigInteger) {
        loadErc20Contract(symbol).mint(address.value, amount).sendAndWaitForConfirmation()
    }

    fun getWalletNativeBalance(): BigInteger {
        return blockchainClientsByChainId.getValue(currentChainId).getNativeBalance(address)
    }

    fun getExchangeERC20Balance(symbol: String): BigInteger {
        return exchangeContractByChainId.getValue(currentChainId).balances(address.value, erc20TokenAddress(symbol)).sendAndWaitForConfirmation()
    }

    fun getExchangeNativeBalance(): BigInteger {
        return exchangeContractByChainId.getValue(currentChainId).balances(address.value, Address.zero.value).sendAndWaitForConfirmation()
    }

    fun asyncDepositNative(amount: BigInteger): TxHash =
        blockchainClientsByChainId.getValue(currentChainId).asyncDepositNative(exchangeContractAddressByChainId.getValue(currentChainId), amount)

    fun depositNative(amount: BigInteger): TransactionReceipt {
        return blockchainClientsByChainId.getValue(currentChainId).depositNative(exchangeContractAddressByChainId.getValue(currentChainId), amount)
    }

    fun asyncDepositERC20(symbol: String, amount: BigInteger): TxHash {
        val erc20Contract = loadErc20Contract(symbol)

        blockchainClientsByChainId.getValue(currentChainId).sendTransaction(
            Address(erc20Contract.contractAddress),
            erc20Contract.approve(exchangeContractAddressByChainId.getValue(currentChainId).value, amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )

        return blockchainClientsByChainId.getValue(currentChainId).sendTransaction(
            Address(exchangeContractByChainId.getValue(currentChainId).contractAddress),
            exchangeContractByChainId.getValue(currentChainId).deposit(erc20TokenAddress(symbol), amount).encodeFunctionCall(),
            BigInteger.ZERO,
        )
    }

    fun depositERC20(symbol: String, amount: BigInteger): TransactionReceipt {
        loadErc20Contract(symbol).approve(exchangeContractAddressByChainId.getValue(currentChainId).value, amount).sendAndWaitForConfirmation()
        return exchangeContractByChainId.getValue(currentChainId).deposit(erc20TokenAddress(symbol), amount).sendAndWaitForConfirmation()
    }

    fun signWithdraw(symbol: String, amount: BigInteger, nonceOverride: Long? = null): CreateWithdrawalApiRequest {
        val nonce = nonceOverride ?: getWithdrawalNonce()
        val tx = EIP712Transaction.WithdrawTx(
            address,
            TokenAddressAndChain(erc20TokenAddress(symbol)?.let { Address(it) } ?: Address.zero, this.currentChainId),
            amount,
            nonce,
            EvmSignature.emptySignature(),
        )
        return CreateWithdrawalApiRequest(
            Symbol(symbol),
            amount,
            nonce,
            blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))),
        )
    }

    fun signOrder(request: CreateOrderApiRequest.Limit): CreateOrderApiRequest.Limit =
        request.copy(
            signature = limitOrderEip712TxSignature(request.marketId, request.amount, request.price, request.side, request.nonce),
            verifyingChainId = this.currentChainId,
        )

    fun signOrder(request: CreateOrderApiRequest.Market): CreateOrderApiRequest.Market {
        val (baseSymbol, quoteSymbol) = marketSymbols(request.marketId)

        val tx = EIP712Transaction.Order(
            address,
            baseToken = baseSymbol.contractAddress ?: Address.zero,
            quoteToken = quoteSymbol.contractAddress ?: Address.zero,
            amount = if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, request.nonce.toHexBytes()),
            signature = EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))),
            verifyingChainId = this.currentChainId,
        )
    }

    fun signOrder(request: UpdateOrderApiRequest): UpdateOrderApiRequest =
        request.copy(
            signature = limitOrderEip712TxSignature(request.marketId, request.amount, request.price, request.side, request.nonce),
            verifyingChainId = this.currentChainId,
        )

    fun signCancelOrder(request: CancelOrderApiRequest): CancelOrderApiRequest {
        val tx = EIP712Transaction.CancelOrder(
            address,
            request.marketId,
            if (request.side == OrderSide.Buy) request.amount else request.amount.negate(),
            BigInteger(1, request.nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )
        return request.copy(
            signature = blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId))),
            verifyingChainId = this.currentChainId,
        )
    }

    private fun limitOrderEip712TxSignature(marketId: MarketId, amount: BigInteger, price: BigDecimal, side: OrderSide, nonce: String): EvmSignature {
        val (baseSymbol, quoteSymbol) = marketSymbols(marketId)
        val tx = EIP712Transaction.Order(
            address,
            baseToken = baseSymbol.contractAddress ?: Address.zero,
            quoteToken = quoteSymbol.contractAddress ?: Address.zero,
            amount = if (side == OrderSide.Buy) amount else amount.negate(),
            price = price.toFundamentalUnits(quoteSymbol.decimals),
            nonce = BigInteger(1, nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )
        return blockchainClientsByChainId.getValue(currentChainId).signData(EIP712Helper.computeHash(tx, this.currentChainId, exchangeContractAddressByChainId.getValue(currentChainId)))
    }

    private fun getWithdrawalNonce(): Long {
        return System.currentTimeMillis()
    }

    fun formatAmount(amount: String, symbol: String): BigInteger {
        return BigDecimal(amount).toFundamentalUnits(decimals(symbol))
    }

    fun decimals(symbol: String): Int = chains.first { it.id == currentChainId }.symbols.first { it.name == symbol }.decimals.toInt()

    private fun loadErc20Contract(symbol: String) = blockchainClientsByChainId.getValue(currentChainId).loadERC20Mock(erc20TokenAddress(symbol)!!)

    private fun erc20TokenAddress(symbol: String) =
        chains.first { it.id == currentChainId }.symbols.firstOrNull { it.name == symbol && it.contractAddress != null }?.contractAddress?.value

    private fun marketSymbols(marketId: MarketId): Pair<SymbolInfo, SymbolInfo> =
        marketId
            .baseAndQuoteSymbols()
            .let { (base, quote) ->
                Pair(
                    chains.first { it.id == currentChainId }.symbols.first { it.name == base },
                    chains.first { it.id == currentChainId }.symbols.first { it.name == quote },
                )
            }
}
