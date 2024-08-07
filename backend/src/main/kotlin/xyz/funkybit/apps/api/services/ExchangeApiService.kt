package xyz.funkybit.apps.api.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.BatchOrdersApiRequest
import xyz.funkybit.apps.api.model.BatchOrdersApiResponse
import xyz.funkybit.apps.api.model.CancelOrderApiRequest
import xyz.funkybit.apps.api.model.CancelOrderApiResponse
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiRequest
import xyz.funkybit.apps.api.model.CreateOrderApiResponse
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.Deposit
import xyz.funkybit.apps.api.model.DepositApiResponse
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.model.RequestStatus
import xyz.funkybit.apps.api.model.Withdrawal
import xyz.funkybit.apps.api.model.WithdrawalApiResponse
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.evm.EIP712Helper
import xyz.funkybit.core.evm.EIP712Transaction
import xyz.funkybit.core.evm.TokenAddressAndChain
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositException
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.OrderEntity
import xyz.funkybit.core.model.db.OrderId
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.sequencer.SequencerClient
import xyz.funkybit.core.sequencer.toSequencerId
import xyz.funkybit.core.services.LinkedSignerService
import xyz.funkybit.core.utils.safeToInt
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.core.utils.toHexBytes
import xyz.funkybit.sequencer.core.Asset
import xyz.funkybit.sequencer.proto.OrderChangeRejected.Reason
import xyz.funkybit.sequencer.proto.SequencerError
import java.math.BigDecimal
import java.math.BigInteger

class ExchangeApiService(
    private val sequencerClient: SequencerClient,
) {
    private val symbolMap = mutableMapOf<Symbol, SymbolEntity>()
    private val marketMap = mutableMapOf<MarketId, Market>()
    private val logger = KotlinLogging.logger {}

    fun addOrder(
        walletAddress: Address,
        apiRequest: CreateOrderApiRequest,
    ): CreateOrderApiResponse {
        return when (apiRequest) {
            is CreateOrderApiRequest.BackToBackMarket -> addBackToBackMarketOrder(
                walletAddress,
                apiRequest,
            )

            else -> orderBatch(
                walletAddress,
                BatchOrdersApiRequest(
                    marketId = apiRequest.marketId,
                    createOrders = listOf(apiRequest),
                    cancelOrders = emptyList(),
                ),
            ).createdOrders.first()
        }
    }

    private fun addBackToBackMarketOrder(
        walletAddress: Address,
        orderRequest: CreateOrderApiRequest.BackToBackMarket,
    ): CreateOrderApiResponse {
        val orderId = OrderId.generate()

        val market1 = getMarket(orderRequest.marketId)
        val baseSymbol = getSymbolEntity(market1.baseSymbol)
        val market2 = getMarket(orderRequest.secondMarketId)
        val quoteSymbol = getSymbolEntity(market2.quoteSymbol)

        verifyEIP712Signature(
            walletAddress,
            EIP712Transaction.Order(
                walletAddress,
                baseChainId = baseSymbol.chainId.value,
                baseToken = baseSymbol.contractAddress ?: Address.zero,
                quoteChainId = quoteSymbol.chainId.value,
                quoteToken = quoteSymbol.contractAddress ?: Address.zero,
                amount = if (orderRequest.side == OrderSide.Buy) orderRequest.amount else orderRequest.amount.negate(),
                price = BigInteger.ZERO,
                nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                signature = orderRequest.signature,
            ),
            verifyingChainId = orderRequest.verifyingChainId,
        )

        val response = runBlocking {
            sequencerClient.backToBackOrder(
                listOf(market1.id, market2.id),
                walletAddress.toSequencerId().value,
                SequencerClient.Order(
                    sequencerOrderId = orderId.toSequencerId().value,
                    amount = orderRequest.amount.fixedAmount(),
                    levelIx = null,
                    orderType = toSequencerOrderType(true, orderRequest.side),
                    nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                    signature = orderRequest.signature,
                    orderId = orderId,
                    chainId = orderRequest.verifyingChainId,
                    clientOrderId = orderRequest.clientOrderId,
                    percentage = orderRequest.amount.percentage(),
                ),
            )
        }

        when (response.error) {
            SequencerError.None -> {}
            SequencerError.ExceedsLimit -> throw RequestProcessingError("Order exceeds limit")
            else -> throw RequestProcessingError("Unable to process request - ${response.error}")
        }

        return CreateOrderApiResponse(orderId, clientOrderId = null, RequestStatus.Accepted, error = null, orderRequest)
    }

    fun orderBatch(
        walletAddress: Address,
        batchOrdersRequest: BatchOrdersApiRequest,
    ): BatchOrdersApiResponse {
        if (batchOrdersRequest.cancelOrders.isEmpty() && batchOrdersRequest.createOrders.isEmpty()) {
            return BatchOrdersApiResponse(emptyList(), emptyList())
        }

        val createOrderRequestsByOrderId = batchOrdersRequest.createOrders.associateBy { OrderId.generate() }

        val market = getMarket(batchOrdersRequest.marketId)
        val baseSymbol = getSymbolEntity(market.baseSymbol)
        val quoteSymbol = getSymbolEntity(market.quoteSymbol)

        val ordersToAdd = createOrderRequestsByOrderId.map { (orderId, orderRequest) ->
            val levelIx = when (orderRequest) {
                is CreateOrderApiRequest.Limit -> {
                    ensurePriceIsMultipleOfTickSize(market, orderRequest.price)
                    orderRequest.price.divideToIntegralValue(market.tickSize).toBigInteger().safeToInt()
                        ?: throw RequestProcessingError("Order price is too large")
                }
                else -> null
            }

            val percentage = when (orderRequest) {
                is CreateOrderApiRequest.Market -> orderRequest.amount.percentage()
                else -> null
            }

            ensureOrderMarketIdMatchesBatchMarketId(orderRequest.marketId, batchOrdersRequest)

            verifyEIP712Signature(
                walletAddress,
                EIP712Transaction.Order(
                    walletAddress,
                    baseChainId = baseSymbol.chainId.value,
                    baseToken = baseSymbol.contractAddress ?: Address.zero,
                    quoteChainId = quoteSymbol.chainId.value,
                    quoteToken = quoteSymbol.contractAddress ?: Address.zero,
                    amount = if (orderRequest.side == OrderSide.Buy) orderRequest.amount else orderRequest.amount.negate(),
                    price = when (orderRequest) {
                        is CreateOrderApiRequest.Limit -> orderRequest.price.toFundamentalUnits(quoteSymbol.decimals)
                        else -> BigInteger.ZERO
                    },
                    nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                    signature = orderRequest.signature,
                ),
                verifyingChainId = orderRequest.verifyingChainId,
            )

            SequencerClient.Order(
                sequencerOrderId = orderId.toSequencerId().value,
                amount = orderRequest.amount.fixedAmount(),
                levelIx = levelIx,
                orderType = toSequencerOrderType(orderRequest is CreateOrderApiRequest.Market, orderRequest.side),
                nonce = BigInteger(1, orderRequest.nonce.toHexBytes()),
                signature = orderRequest.signature,
                orderId = orderId,
                chainId = orderRequest.verifyingChainId,
                clientOrderId = orderRequest.clientOrderId,
                percentage = percentage,
            )
        }

        val ordersToCancel = batchOrdersRequest.cancelOrders.map { orderRequest ->
            ensureOrderMarketIdMatchesBatchMarketId(orderRequest.marketId, batchOrdersRequest)

            verifyEIP712Signature(
                walletAddress,
                EIP712Transaction.CancelOrder(
                    walletAddress,
                    batchOrdersRequest.marketId,
                    if (orderRequest.side == OrderSide.Buy) orderRequest.amount else orderRequest.amount.negate(),
                    BigInteger(1, orderRequest.nonce.toHexBytes()),
                    orderRequest.signature,
                ),
                verifyingChainId = orderRequest.verifyingChainId,
            )

            orderRequest.orderId
        }

        val response = runBlocking {
            sequencerClient.orderBatch(market.id, walletAddress.toSequencerId().value, ordersToAdd, ordersToCancel)
        }

        when (response.error) {
            SequencerError.None -> {}
            SequencerError.ExceedsLimit -> throw RequestProcessingError("Order exceeds limit")
            else -> throw RequestProcessingError("Unable to process request - ${response.error}")
        }

        val failedUpdatesOrCancels = response.ordersChangeRejectedList.associateBy { it.guid }

        return BatchOrdersApiResponse(
            createOrderRequestsByOrderId.map { (orderId, request) ->
                CreateOrderApiResponse(
                    orderId = orderId,
                    clientOrderId = request.clientOrderId,
                    requestStatus = RequestStatus.Accepted,
                    error = null,
                    order = request,
                )
            },
            batchOrdersRequest.cancelOrders.map {
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

    fun withdraw(walletAddress: Address, apiRequest: CreateWithdrawalApiRequest): WithdrawalApiResponse {
        val symbol = getSymbolEntity(apiRequest.symbol)

        verifyEIP712Signature(
            walletAddress,
            EIP712Transaction.WithdrawTx(
                walletAddress,
                TokenAddressAndChain(symbol.contractAddress ?: Address.zero, symbol.chainId.value),
                apiRequest.amount,
                apiRequest.nonce,
                apiRequest.amount == BigInteger.ZERO,
                apiRequest.signature,
            ),
            symbol.chainId.value,
        )

        val withdrawal = transaction {
            WithdrawalEntity.createPending(
                WalletEntity.getByAddress(walletAddress),
                symbol,
                apiRequest.amount,
                apiRequest.nonce,
                apiRequest.signature,
            ).let {
                it.refresh(flush = true)
                Withdrawal.fromEntity(it)
            }
        }

        runBlocking {
            sequencerClient.withdraw(
                walletAddress.toSequencerId().value,
                Asset(symbol.name),
                apiRequest.amount,
                apiRequest.nonce.toBigInteger(),
                apiRequest.signature,
                withdrawal.id,
            )
        }

        return WithdrawalApiResponse(withdrawal)
    }

    fun deposit(walletAddress: Address, apiRequest: CreateDepositApiRequest): DepositApiResponse =
        transaction {
            val deposit = DepositEntity.createOrUpdate(
                wallet = WalletEntity.getOrCreate(walletAddress),
                symbol = getSymbolEntity(apiRequest.symbol),
                amount = apiRequest.amount,
                blockNumber = null,
                transactionHash = apiRequest.txHash,
            ) ?: throw DepositException("Unable to create deposit")

            DepositApiResponse(Deposit.fromEntity(deposit))
        }

    fun cancelOrder(walletAddress: Address, cancelOrderApiRequest: CancelOrderApiRequest): CancelOrderApiResponse {
        return orderBatch(
            walletAddress,
            BatchOrdersApiRequest(
                marketId = cancelOrderApiRequest.marketId,
                createOrders = emptyList(),
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
        }
    }

    private fun getSymbolEntity(asset: Symbol): SymbolEntity {
        return symbolMap.getOrPut(asset) {
            transaction { SymbolEntity.forName(asset.value) }
        }
    }

    private fun getMarket(marketId: MarketId): Market {
        return marketMap.getOrPut(marketId) {
            transaction {
                MarketEntity[marketId].let {
                    Market(
                        it.id.value,
                        baseSymbol = Symbol(it.baseSymbol.name),
                        baseDecimals = it.baseSymbol.decimals.toInt(),
                        quoteSymbol = Symbol(it.quoteSymbol.name),
                        quoteDecimals = it.quoteSymbol.decimals.toInt(),
                        tickSize = it.tickSize,
                        lastPrice = it.lastPrice,
                        minFee = it.minFee,
                    )
                }
            }
        }
    }

    private fun toSequencerOrderType(isMarketOrder: Boolean, side: OrderSide): xyz.funkybit.sequencer.proto.Order.Type {
        return when (isMarketOrder) {
            true ->
                when (side) {
                    OrderSide.Buy -> xyz.funkybit.sequencer.proto.Order.Type.MarketBuy
                    OrderSide.Sell -> xyz.funkybit.sequencer.proto.Order.Type.MarketSell
                }

            false ->
                when (side) {
                    OrderSide.Buy -> xyz.funkybit.sequencer.proto.Order.Type.LimitBuy
                    OrderSide.Sell -> xyz.funkybit.sequencer.proto.Order.Type.LimitSell
                }
        }
    }

    private fun ensurePriceIsMultipleOfTickSize(market: Market, price: BigDecimal) {
        if (BigDecimal.ZERO.compareTo(price.remainder(market.tickSize)) != 0) {
            throw RequestProcessingError("Order price is not a multiple of tick size")
        }
    }

    private fun ensureOrderMarketIdMatchesBatchMarketId(orderMarketId: MarketId, apiRequest: BatchOrdersApiRequest) {
        if (orderMarketId != apiRequest.marketId) {
            throw RequestProcessingError("Orders in a batch request have to be in the same market")
        }
    }

    private val exchangeContractsByChain = mutableMapOf<ChainId, Address>()

    private fun verifyEIP712Signature(walletAddress: Address, tx: EIP712Transaction, verifyingChainId: ChainId) {
        val verifyingContract = exchangeContractsByChain[verifyingChainId] ?: transaction {
            DeployedSmartContractEntity.latestExchangeContractAddress(verifyingChainId)?.also {
                exchangeContractsByChain[verifyingChainId] = it
            } ?: throw RequestProcessingError("Exchange contract not found for $verifyingChainId")
        }

        runCatching {
            ECHelper.isValidSignature(
                EIP712Helper.computeHash(tx, verifyingChainId, verifyingContract),
                tx.signature,
                walletAddress,
                LinkedSignerService.getLinkedSigner(walletAddress, verifyingChainId),
            )
        }.onFailure {
            logger.warn(it) { "Exception verifying EIP712 signature" }
            throw RequestProcessingError(ReasonCode.SignatureNotValid, "Invalid signature")
        }.getOrDefault(false).also { isValidSignature ->
            if (!isValidSignature) {
                throw RequestProcessingError(ReasonCode.SignatureNotValid, "Invalid signature")
            }
        }
    }
}
