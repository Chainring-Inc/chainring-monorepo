package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.NetworkType

@Serializable
data class ConfigurationApiResponse(
    val chains: List<Chain>,
    val markets: List<Market>,
    val feeRates: FeeRates,
) {
    val evmChains: List<Chain>
        get() = chains.filter { it.networkType == NetworkType.Evm }
}

enum class Role {
    User,
    Admin,
}

@Serializable
data class AccountConfigurationApiResponse(
    val newSymbols: List<SymbolInfo>,
    val role: Role,
)

@Serializable
data class Chain(
    val id: ChainId,
    val name: String,
    val contracts: List<DeployedContract>,
    val symbols: List<SymbolInfo>,
    val jsonRpcUrl: String,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
    val networkType: NetworkType,
)

@Serializable
data class DeployedContract(
    val name: String,
    val address: Address,
)

@Serializable
data class SymbolInfo(
    val name: String,
    val description: String,
    val contractAddress: Address?,
    val decimals: UByte,
    val faucetSupported: Boolean,
    val iconUrl: String,
    val withdrawalFee: BigIntegerJson,
)

@Serializable
data class Market(
    val id: MarketId,
    val baseSymbol: Symbol,
    val baseDecimals: Int,
    val quoteSymbol: Symbol,
    val quoteDecimals: Int,
    val tickSize: BigDecimalJson,
    val lastPrice: BigDecimalJson,
    val minFee: BigIntegerJson,
)
