package xyz.funkybit.testfixtures

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.SequencerOrderId
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.OrderType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

object DbTestHelpers {
    fun createChain(
        id: ChainId,
        name: String,
        jsonRpcUrl: String = "",
        blockExplorerNetName: String = "",
        blockExplorerUrl: String = "",
        networkType: NetworkType = NetworkType.Evm,
    ): ChainEntity =
        ChainEntity.create(id, name, jsonRpcUrl, blockExplorerNetName, blockExplorerUrl, networkType)

    fun createNativeSymbol(
        name: String,
        chainId: ChainId,
        decimals: UByte,
        withdrawalFee: BigInteger = BigInteger.ZERO,
    ): SymbolEntity =
        SymbolEntity.create(
            name,
            chainId,
            contractAddress = null,
            decimals = decimals,
            description = "native coin",
            withdrawalFee = withdrawalFee,
        )

    fun createSymbol(
        name: String,
        chainId: ChainId,
        decimals: UByte,
        withdrawalFee: BigInteger = BigInteger.ZERO,
    ): SymbolEntity =
        SymbolEntity.create(
            name,
            chainId,
            contractAddress = Address.generate(),
            decimals = decimals,
            description = "$name coin",
            withdrawalFee = withdrawalFee,
        )

    fun createMarket(
        baseSymbol: SymbolEntity,
        quoteSymbol: SymbolEntity,
        tickSize: BigDecimal,
        lastPrice: BigDecimal,
    ): MarketEntity =
        MarketEntity.create(baseSymbol, quoteSymbol, tickSize, lastPrice, "test")

    fun createWallet(address: Address = Address.generate()): WalletEntity =
        WalletEntity.getOrCreate(address)

    fun createOrder(
        market: MarketEntity,
        wallet: WalletEntity,
        side: OrderSide,
        type: OrderType,
        amount: BigDecimal,
        price: BigDecimal?,
        status: OrderStatus,
        sequencerId: SequencerOrderId,
        id: OrderId = OrderId.generate(),
        createdAt: Instant = Clock.System.now(),
    ): OrderEntity =
        OrderEntity.new(id) {
            this.createdAt = createdAt
            this.createdBy = "system"
            this.marketGuid = market.guid
            this.walletGuid = wallet.guid
            this.status = status
            this.side = side
            this.type = type
            this.amount = amount.toFundamentalUnits(market.baseSymbol.decimals)
            this.originalAmount = this.amount
            this.price = price
            this.nonce = UUID.randomUUID().toString().replace("-", "")
            this.signature = "signature"
            this.sequencerOrderId = sequencerId
            this.sequencerTimeNs = BigInteger.ZERO
        }
}
