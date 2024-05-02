package co.chainring.apps.api.services

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.BatchOrdersApiResponse
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.CancelOrderApiResponse
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.Market
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.RequestStatus
import co.chainring.apps.api.model.Symbol
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.UpdateOrderApiResponse
import co.chainring.apps.api.model.websocket.Orders
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.ExchangeError
import co.chainring.core.model.db.BroadcasterNotification
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.publishBroadcasterNotifications
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.sequencer.toSequencerId
import co.chainring.core.utils.toHexBytes
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.proto.OrderChangeRejected.Reason
import co.chainring.sequencer.proto.SequencerError
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.BigInteger

class ExchangeApiService(
    val blockchainClient: BlockchainClient,
    val sequencerClient: SequencerClient,
) {
    private val symbolMap = mutableMapOf<String, Symbol>()
    private val marketMap = mutableMapOf<MarketId, Market>()
    private val logger = KotlinLogging.logger {}

    fun addOrder(
        walletAddress: Address,
        apiRequest: CreateOrderApiRequest,
    ): CreateOrderApiResponse {
        return orderBatch(
            walletAddress,
            BatchOrdersApiRequest(
                marketId = apiRequest.marketId,
                createOrders = listOf(apiRequest),
                updateOrders = emptyList(),
                cancelOrders = emptyList(),
            ),
        ).createdOrders.first()
    }

    fun updateOrder(
        walletAddress: Address,
        apiRequest: UpdateOrderApiRequest,
    ): UpdateOrderApiResponse {
        return orderBatch(
            walletAddress,
            BatchOrdersApiRequest(
                marketId = apiRequest.marketId,
                createOrders = emptyList(),
                updateOrders = listOf(apiRequest),
                cancelOrders = emptyList(),
            ),
        ).updatedOrders.first()
    }

    fun orderBatch(
        walletAddress: Address,
        apiRequest: BatchOrdersApiRequest,
    ): BatchOrdersApiResponse {
        if (apiRequest.cancelOrders.isEmpty() && apiRequest.updateOrders.isEmpty() && apiRequest.createOrders.isEmpty()) {
            return BatchOrdersApiResponse(emptyList(), emptyList(), emptyList())
        }

        val createOrderRequestsByOrderId = apiRequest.createOrders.associateBy { OrderId.generate() }

        val market = getMarket(apiRequest.marketId)

        // process the orders to add
        val ordersToAdd = createOrderRequestsByOrderId.map { (orderId, request) ->
            // check price and market
            val price = checkPrice(market, request.getResolvedPrice())
            checkMarket(apiRequest.marketId, request.marketId)
            // verify signatures on created orders
            validateOrderSignature(
                walletAddress,
                request.toEip712Transaction(walletAddress, getSymbol(market.baseSymbol), getSymbol(market.quoteSymbol)),
            )
            SequencerClient.Order(
                sequencerOrderId = orderId.toSequencerId().value,
                amount = request.amount,
                price = price?.toString(),
                wallet = walletAddress.toSequencerId().value,
                orderType = toSequencerOrderType(request is CreateOrderApiRequest.Market, request.side),
                nonce = BigInteger(1, request.nonce.toHexBytes()),
                signature = request.signature,
                orderId = orderId,
            )
        }

        // process any order updates
        val ordersToUpdate = apiRequest.updateOrders.map { request ->
            val price = checkPrice(market, request.getResolvedPrice())
            checkMarket(apiRequest.marketId, request.marketId)
            // verify signatures on updated orders
            validateOrderSignature(
                walletAddress,
                request.toEip712Transaction(walletAddress, getSymbol(market.baseSymbol), getSymbol(market.quoteSymbol)),
            )
            SequencerClient.Order(
                sequencerOrderId = request.orderId.toSequencerId().value,
                amount = request.amount,
                price = price?.toString(),
                wallet = walletAddress.toSequencerId().value,
                orderType = toSequencerOrderType(false, request.side),
                nonce = BigInteger(1, request.nonce.toHexBytes()),
                signature = request.signature,
                orderId = request.orderId,
            )
        }

        // cancels
        val ordersToCancel = apiRequest.cancelOrders.map { request ->
            checkMarket(apiRequest.marketId, request.marketId)
            // verify signatures on cancelled orders
            validateOrderSignature(
                walletAddress,
                request.toEip712Transaction(walletAddress),
            )
            request.orderId
        }

        val response = runBlocking {
            sequencerClient.orderBatch(MarketId(market.id), walletAddress.toSequencerId().value, ordersToAdd, ordersToUpdate, ordersToCancel)
        }

        when (response.error) {
            SequencerError.None -> {}
            SequencerError.ExceedsLimit -> throw ExchangeError("Order exceeds limit")
            else -> throw ExchangeError("Unable to process request - ${response.error}")
        }

        val failedUpdatesOrCancels = response.ordersChangeRejectedList.associateBy { it.guid }

        return BatchOrdersApiResponse(
            createOrderRequestsByOrderId.map { (orderId, request) ->
                CreateOrderApiResponse(
                    orderId = orderId,
                    requestStatus = RequestStatus.Accepted,
                    error = null,
                    order = request,
                )
            },
            apiRequest.updateOrders.map {
                val rejected = failedUpdatesOrCancels[it.orderId.toSequencerId().value]
                UpdateOrderApiResponse(
                    requestStatus = if (rejected == null) RequestStatus.Accepted else RequestStatus.Rejected,
                    error = rejected?.reason?.let { reason -> ApiError(ReasonCode.RejectedBySequencer, reasonToMessage(reason)) },
                    order = it,
                )
            },
            apiRequest.cancelOrders.map {
                val rejected = failedUpdatesOrCancels[it.orderId.toSequencerId().value]
                CancelOrderApiResponse(
                    orderId = it.orderId,
                    requestStatus = if (rejected == null) RequestStatus.Accepted else RequestStatus.Rejected,
                    error = rejected?.reason?.let { reason -> ApiError(ReasonCode.RejectedBySequencer, reasonToMessage(reason)) },
                )
            },
        )
    }

    private fun reasonToMessage(reason: Reason): String {
        return when (reason) {
            Reason.DoesNotExist -> "Order does not exist or is already finalized"
            Reason.NotForWallet -> "Order not created by this wallet"
            else -> ""
        }
    }

    fun withdraw(withdrawTx: EIP712Transaction.WithdrawTx): WithdrawalId {
        val withdrawalId = WithdrawalId.generate()
        val withdrawalEntity = transaction {
            WithdrawalEntity.create(
                withdrawalId,
                withdrawTx.nonce,
                blockchainClient.chainId,
                WalletEntity.getByAddress(withdrawTx.sender)!!,
                withdrawTx.token,
                withdrawTx.amount,
                withdrawTx.signature,
            )
        }

        runBlocking {
            sequencerClient.withdraw(
                withdrawTx.sender.toSequencerId().value,
                Asset(transaction { withdrawalEntity.symbol.name }),
                withdrawTx.amount,
                withdrawTx.nonce.toBigInteger(),
                withdrawTx.signature,
                withdrawalId,
            )
        }

        return withdrawalEntity.guid.value
    }

    fun cancelOrder(walletAddress: Address, cancelOrderApiRequest: CancelOrderApiRequest): CancelOrderApiResponse {
        return orderBatch(
            walletAddress,
            BatchOrdersApiRequest(
                marketId = cancelOrderApiRequest.marketId,
                createOrders = emptyList(),
                updateOrders = emptyList(),
                cancelOrders = listOf(cancelOrderApiRequest),
            ),
        ).canceledOrders.first()
    }

    fun cancelOpenOrders(walletEntity: WalletEntity) {
        val openOrders = transaction {
            OrderEntity.listOpenForWallet(walletEntity)
        }
        if (openOrders.isNotEmpty()) {
            runBlocking {
                openOrders.groupBy { it.marketGuid }.forEach { entry ->
                    val orderIds = entry.value.map { it.guid.value }
                    sequencerClient.cancelOrders(
                        entry.key.value,
                        walletEntity.address.toSequencerId().value,
                        orderIds,
                        cancelAll = true,
                    )
                }
            }
        } else {
            transaction {
                publishBroadcasterNotifications(
                    listOf(
                        BroadcasterNotification(
                            Orders(
                                OrderEntity
                                    .listForWallet(walletEntity)
                                    .map(OrderEntity::toOrderResponse),
                            ),
                            walletEntity.address,
                        ),
                    ),
                )
            }
        }
    }

    private fun getSymbol(asset: String): Symbol {
        return symbolMap.getOrPut(asset) {
            transaction { SymbolEntity.forChainAndName(blockchainClient.chainId, asset) }.let {
                Symbol(it.name, it.description, it.contractAddress, it.decimals)
            }
        }
    }

    private fun getMarket(marketId: MarketId): Market {
        return marketMap.getOrPut(marketId) {
            transaction {
                MarketEntity[marketId].let {
                    Market(it.id.value.value, it.baseSymbol.name, it.baseSymbol.decimals.toInt(), it.quoteSymbol.name, it.quoteSymbol.decimals.toInt(), it.tickSize)
                }
            }
        }
    }

    private fun toSequencerOrderType(isMarketOrder: Boolean, side: OrderSide): co.chainring.sequencer.proto.Order.Type {
        return when (isMarketOrder) {
            true ->
                when (side) {
                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.MarketBuy
                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.MarketSell
                }

            false ->
                when (side) {
                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.LimitBuy
                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.LimitSell
                }
        }
    }

    private fun checkPrice(market: Market, price: BigDecimal?): BigDecimal? {
        if (price != null && BigDecimal.ZERO.compareTo(price.remainder(market.tickSize)) != 0) {
            throw ExchangeError("Order price is not a multiple of tick size")
        }
        return price
    }

    private fun checkMarket(left: MarketId, right: MarketId) {
        if (left != right) {
            throw ExchangeError("Markets are different")
        }
    }

    private fun validateOrderSignature(walletAddress: Address, tx: EIP712Transaction) {
        val verifyingContract = blockchainClient.getContractAddress(ContractType.Exchange)
            ?: throw ExchangeError("No deployed contract found")

        runCatching {
            ECHelper.isValidSignature(
                EIP712Helper.computeHash(
                    tx,
                    blockchainClient.chainId,
                    verifyingContract,
                ),
                tx.signature,
                walletAddress,
            )
        }.onFailure {
            logger.warn(it) { "Exception validating signature" }
            throw ExchangeError("Invalid signature")
        }.getOrDefault(false).also { isValidSignature ->
            if (!isValidSignature) {
                throw ExchangeError("Invalid signature")
            }
        }
    }

//    private fun validateOrderSignature(walletAddress: Address, marketId: MarketId, amount: BigInteger, side: OrderSide, price: BigDecimal?, nonce: String, signature: EvmSignature, isCancel: Boolean = false) {
//        val verifyingContract = blockchainClient.getContractAddress(ContractType.Exchange)
//            ?: throw ExchangeError("No deployed contract found")
//
//        runCatching {
//            val tx = if (isCancel) {
//                EIP712Transaction.CancelOrder(
//                    walletAddress,
//                    marketId,
//                    if (side == OrderSide.Buy) amount else amount.negate(),
//                    BigInteger(1, nonce.toHexBytes()),
//                    signature,
//                )
//            } else {
//                val (baseSymbol, quoteSymbol) = marketId.value.split("/")
//                EIP712Transaction.Order(
//                    walletAddress,
//                    getContractAddress(baseSymbol),
//                    getContractAddress(quoteSymbol),
//                    if (side == OrderSide.Buy) amount else amount.negate(),
//                    price?.toFundamentalUnits(getSymbol(quoteSymbol).decimals) ?: BigInteger.ZERO,
//                    BigInteger(1, nonce.toHexBytes()),
//                    signature,
//                )
//            }
//
//            ECHelper.isValidSignature(
//                EIP712Helper.computeHash(
//                    tx,
//                    blockchainClient.chainId,
//                    verifyingContract,
//                ),
//                tx.signature,
//                walletAddress,
//            )
//        }.onFailure {
//            logger.warn(it) { "Exception validating signature" }
//            throw ExchangeError("Invalid signature")
//        }.getOrDefault(false).also { isValidSignature ->
//            if (!isValidSignature) {
//                throw ExchangeError("Invalid signature")
//            }
//        }
//    }
}
