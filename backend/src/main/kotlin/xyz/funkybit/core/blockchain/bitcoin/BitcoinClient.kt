package xyz.funkybit.core.blockchain.bitcoin

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import org.bitcoinj.core.NetworkParameters
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.blockchain.SmartFeeMode
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.rpc.BitcoinRpc
import xyz.funkybit.core.model.rpc.BitcoinRpcParams
import xyz.funkybit.core.model.rpc.BitcoinRpcRequest
import java.math.BigDecimal
import java.math.RoundingMode

object BitcoinClient : JsonRpcClientBase(
    ChainManager.bitcoinBlockchainClientConfig.url,
    if (ChainManager.bitcoinBlockchainClientConfig.enableBasicAuth) {
        BasicAuthInterceptor(ChainManager.bitcoinBlockchainClientConfig.user, ChainManager.bitcoinBlockchainClientConfig.password)
    } else {
        null
    },
) {

    override val logger = KotlinLogging.logger {}

    val bitcoinConfig = ChainManager.bitcoinBlockchainClientConfig

    val chainId = ChainId(8086u)

    fun getParams(): NetworkParameters = NetworkParameters.fromID(ChainManager.bitcoinBlockchainClientConfig.net)!!

    fun getBlockCount(): Long {
        return getValue(
            BitcoinRpcRequest(
                "getblockcount",
            ),
        )
    }

    fun getBlockHash(blockHeight: Long): String {
        return getValue(
            BitcoinRpcRequest(
                "getblockhash",
                BitcoinRpcParams(listOf(blockHeight)),
            ),
        )
    }

    fun getBlock(blockhash: String, verbosity: Int = 2): BitcoinRpc.Block {
        return getValue(
            BitcoinRpcRequest(
                "getblock",
                BitcoinRpcParams(listOf(blockhash, verbosity)),
            ),
            false,
        )
    }

    fun getBlockHeader(blockhash: String): BitcoinRpc.Block {
        return getValue(
            BitcoinRpcRequest(
                "getblockheader",
                BitcoinRpcParams(listOf(blockhash)),
            ),
            true,
        )
    }

    open fun getRawTransaction(txId: String): BitcoinRpc.Transaction {
        return getValue(
            BitcoinRpcRequest(
                "getrawtransaction",
                BitcoinRpcParams(listOf(txId, true)),
            ),
            false,
        )
    }

    fun sendRawTransaction(rawTransactionHex: String): String {
        return getValue(
            BitcoinRpcRequest(
                "sendrawtransaction",
                BitcoinRpcParams(listOf(rawTransactionHex)),
            ),
            true,
        )
    }

    fun estimateSmartFeeInSatPerVByte(): BigDecimal? {
        return estimateSmartFee(bitcoinConfig.feeSettings.blocks, bitcoinConfig.feeSettings.mode).feeRate?.let {
            scaleToSatoshiPerVByte(it)
        }
    }

    private fun scaleToSatoshiPerVByte(btcPerVirtualKByte: BigDecimal) =
        (btcPerVirtualKByte.setScale(8) * BigDecimal("100000000") / BigDecimal("1024")).setScale(0, RoundingMode.UP)

    private fun estimateSmartFee(confBlocks: Int, smartFeeMode: SmartFeeMode?): BitcoinRpc.SmartFeeInfo {
        return getValue(
            BitcoinRpcRequest(
                "estimatesmartfee",
                BitcoinRpcParams(listOfNotNull(confBlocks, smartFeeMode?.name)),
            ),
            true,
        )
    }

    inline fun <reified T> getValue(request: BitcoinRpcRequest, logResponseBody: Boolean = true): T {
        val jsonElement = call(json.encodeToString(request), logResponseBody)
        return json.decodeFromJsonElement(jsonElement)
    }
}
