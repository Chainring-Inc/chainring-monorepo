package co.chainring.core.services

import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.blockchain.DepositConfirmationCallback
import co.chainring.core.db.notifyDbListener
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.model.Address
import co.chainring.core.model.BroadcasterNotification
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.ExchangeError
import co.chainring.core.model.PrincipalNotifications
import co.chainring.core.model.SequencerOrderId
import co.chainring.core.model.SequencerWalletId
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.BalanceChange
import co.chainring.core.model.db.BalanceEntity
import co.chainring.core.model.db.BalanceType
import co.chainring.core.model.db.CreateOrderAssignment
import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.ExecutionId
import co.chainring.core.model.db.ExecutionRole
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderSide
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.OrderType
import co.chainring.core.model.db.SymbolEntity
import co.chainring.core.model.db.TradeEntity
import co.chainring.core.model.db.UpdateOrderAssignment
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.sequencer.SequencerClient
import co.chainring.core.sequencer.sequencerOrderId
import co.chainring.core.sequencer.toSequencerId
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHexBytes
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.BigInteger

typealias BroadcasterNotifications = MutableMap<Address, MutableList<BroadcasterNotification>>
fun BroadcasterNotifications.add(address: Address, notification: BroadcasterNotification) {
    this.getOrPut(address) { mutableListOf() }.add(notification)
}

typealias WalletOrders = MutableMap<Address, MutableList<OrderId>>
fun WalletOrders.add(address: Address, orderId: OrderId) {
    this.getOrPut(address) { mutableListOf() }.add(orderId)
}

typealias WalletExecutions = MutableMap<Address, MutableList<ExecutionId>>
fun WalletExecutions.add(address: Address, executionId: ExecutionId) {
    this.getOrPut(address) { mutableListOf() }.add(executionId)
}

interface TxConfirmationCallback {
    fun onTxConfirmation(tx: EIP712Transaction, error: String?)
}

class ExchangeService(
    val blockchainClient: BlockchainClient,
    val sequencerClient: SequencerClient,
) : TxConfirmationCallback, DepositConfirmationCallback {

    private val symbolMap = mutableMapOf<String, SymbolEntity>()
    private val marketMap = mutableMapOf<MarketId, MarketEntity>()
    private val logger = KotlinLogging.logger {}

    fun addOrder(
        walletAddress: Address,
        apiRequest: CreateOrderApiRequest,
    ): Order {
        return orderBatch(
            walletAddress,
            BatchOrdersApiRequest(
                marketId = apiRequest.marketId,
                createOrders = listOf(apiRequest),
                updateOrders = emptyList(),
                cancelOrders = emptyList(),
            ),
        ).first()
    }

    fun updateOrder(
        walletAddress: Address,
        orderEntity: OrderEntity,
        apiRequest: UpdateOrderApiRequest,
    ): Order {
        return transaction {
            orderBatch(
                walletAddress,
                BatchOrdersApiRequest(
                    marketId = orderEntity.market.guid.value,
                    createOrders = emptyList(),
                    updateOrders = listOf(apiRequest),
                    cancelOrders = emptyList(),
                ),
            )
        }.first()
    }

    fun orderBatch(
        walletAddress: Address,
        apiRequest: BatchOrdersApiRequest,
    ): List<Order> {
        val broadcasterNotifications: BroadcasterNotifications = mutableMapOf()

        if (apiRequest.cancelOrders.isEmpty() && apiRequest.updateOrders.isEmpty() && apiRequest.createOrders.isEmpty()) {
            return emptyList()
        }

        val sequencerOrderIdsBeingUpdated = mutableListOf<SequencerOrderId>()
        val updatedAndCancelsOrderIds = mutableListOf<OrderId>()

        val (sequencerResponse, createdOrders) = transaction {
            val market = getMarket(apiRequest.marketId)

            val wallet = WalletEntity.getOrCreate(walletAddress)
            val createAssignments = mutableListOf<CreateOrderAssignment>()
            val updateAssignments = mutableListOf<UpdateOrderAssignment>()

            // process the orders to add
            val ordersToAdd = apiRequest.createOrders.map {
                // check price and market
                val price = checkPrice(market, it.getResolvedPrice())
                checkMarket(apiRequest.marketId, it.marketId)
                // verify signatures on created owners
                validateOrderSignature(
                    walletAddress,
                    it.marketId,
                    it.amount,
                    it.side,
                    price,
                    it.nonce,
                    it.signature,
                )
                val orderId = OrderId.generate()
                val sequencerOrderId = orderId.toSequencerId()
                createAssignments.add(
                    CreateOrderAssignment(
                        orderId,
                        it.nonce,
                        it.getOrderType(),
                        it.side,
                        it.amount,
                        it.getResolvedPrice(),
                        it.signature,
                        sequencerOrderId,
                    ),
                )
                SequencerClient.Order(
                    sequencerOrderId = sequencerOrderId.value,
                    amount = it.amount,
                    price = price?.toString(),
                    wallet = wallet.sequencerId.value,
                    orderType = getSequencerOrderType(it),
                )
            }

            // process any order updates
            val ordersToUpdate = if (apiRequest.updateOrders.isNotEmpty()) {
                val ordersEntitiesToUpdate =
                    OrderEntity.listOrders(apiRequest.updateOrders.map { it.orderId }).associateBy { it.guid.value }
                sequencerOrderIdsBeingUpdated.addAll(ordersEntitiesToUpdate.values.mapNotNull { it.sequencerOrderId })
                updatedAndCancelsOrderIds.addAll(ordersEntitiesToUpdate.values.map { it.guid.value })
                apiRequest.updateOrders.map {
                    val orderEntity = ordersEntitiesToUpdate.getValue(it.orderId)
                    // check price, market and owner
                    val price = checkPrice(market, it.getResolvedPrice())
                    checkMarket(apiRequest.marketId, orderEntity.marketGuid.value)
                    checkIsOwner(orderEntity.wallet.address, walletAddress)
                    updateAssignments.add(
                        UpdateOrderAssignment(it.orderId, it.amount, price),
                    )
                    SequencerClient.Order(
                        sequencerOrderId = orderEntity.sequencerOrderId!!.value,
                        amount = it.amount,
                        price = price?.toString(),
                        wallet = wallet.sequencerId.value,
                        orderType = when (it) {
                            is UpdateOrderApiRequest.Market ->
                                when (orderEntity.side) {
                                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.MarketBuy
                                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.MarketSell
                                }

                            is UpdateOrderApiRequest.Limit ->
                                when (orderEntity.side) {
                                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.LimitBuy
                                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.LimitSell
                                }
                        },
                    )
                }
            } else {
                emptyList()
            }

            // process any order cancellations
            val ordersToCancel = if (apiRequest.cancelOrders.isNotEmpty()) {
                val ordersToCancel =
                    OrderEntity.listOrders(apiRequest.cancelOrders.map { it.orderId }).associateBy { it.guid.value }
                updatedAndCancelsOrderIds.addAll(ordersToCancel.values.map { it.guid.value })
                apiRequest.cancelOrders.map {
                    val orderEntity = ordersToCancel.getValue(it.orderId)
                    checkMarket(apiRequest.marketId, orderEntity.marketGuid.value)
                    checkIsOwner(orderEntity.wallet.address, walletAddress)
                    orderEntity.sequencerOrderId!!.value
                }
            } else {
                emptyList()
            }

            val response = runBlocking {
                sequencerClient.orderBatch(market.id.value, ordersToAdd, ordersToUpdate, ordersToCancel)
            }

            // apply db changes amd send the OrderCreated notification
            OrderEntity.batchUpdate(market, wallet, createAssignments, updateAssignments)
            val createdOrders = if (createAssignments.isNotEmpty()) {
                val orders = OrderEntity.listOrders(createAssignments.map { it.orderId }).map { it.toOrderResponse() }
                broadcasterNotifications.add(wallet.address, BroadcasterNotification.OrdersCreated(orders.map { it.id }))
                orders
            } else {
                emptyList()
            }

            if (response.error != SequencerError.None) {
                throw ExchangeError("Unable to process request - ${response.error}")
            }

            Pair(response, createdOrders)
        }

        // handler the sequencer response and send back orders for all added, updated or cancelled orders
        return transaction {
            handleSequencerResponse(sequencerResponse, broadcasterNotifications, sequencerOrderIdsBeingUpdated)
            createdOrders + OrderEntity.listOrders(updatedAndCancelsOrderIds).map { it.toOrderResponse() }
        }
    }

    fun withdraw(withdrawTx: EIP712Transaction.WithdrawTx): WithdrawalId {
        return transaction {
            val withdrawalEntity = WithdrawalEntity.create(
                withdrawTx.nonce,
                blockchainClient.chainId,
                WalletEntity.getOrCreate(withdrawTx.sender),
                withdrawTx.token,
                withdrawTx.amount,
                withdrawTx.signature,
            )

            val response = runBlocking {
                sequencerClient.withdraw(
                    withdrawalEntity.wallet.sequencerId.value,
                    Asset(withdrawalEntity.symbol.name),
                    withdrawalEntity.amount,
                )
            }
            if (response.balancesChangedList.isEmpty()) {
                // if this did not result in a balance change fail the withdrawal since sequencer rejected it for some reason
                withdrawalEntity.update(WithdrawalStatus.Failed, "Rejected by sequencer")
            } else {
                handleSequencerResponse(response, mutableMapOf())
                blockchainClient.queueTransactions(listOf(withdrawalEntity.toEip712Transaction()))
            }
            withdrawalEntity.guid.value
        }
    }

    fun cancelOrder(walletAddress: Address, orderEntity: OrderEntity) {
        val response = runBlocking {
            checkIsOwner(orderEntity.wallet.address, walletAddress)
            sequencerClient.cancelOrder(
                sequencerOrderId = orderEntity.sequencerOrderId!!.value,
                marketId = orderEntity.market.guid.value,
            )
        }
        handleSequencerResponse(response, mutableMapOf())
    }

    fun cancelOpenOrders(walletEntity: WalletEntity) {
        transaction {
            val openOrders = OrderEntity.listOpenForWallet(walletEntity)
            if (openOrders.isNotEmpty()) {
                runBlocking {
                    openOrders.groupBy { it.marketGuid }.forEach { entry ->
                        val sequencerOrderIds = entry.value.mapNotNull { it.sequencerOrderId?.value }
                        sequencerClient.cancelOrders(entry.key.value, sequencerOrderIds)
                    }
                }
                OrderEntity.cancelAll(walletEntity)
                listOf(PrincipalNotifications(walletEntity.address, listOf(BroadcasterNotification.Orders))).send()
            }
        }
    }

    private fun handleSequencerResponse(response: SequencerResponse, broadcasterNotifications: BroadcasterNotifications, ordersBeingUpdated: List<SequencerOrderId> = listOf()) {
        val timestamp = Clock.System.now()

        // handle trades
        val walletExecutionIds: WalletExecutions = mutableMapOf()
        val blockchainTxs = response.tradesCreatedList.mapNotNull {
            logger.debug { "Trade Created ${it.buyGuid}, ${it.sellGuid}, ${it.amount.toBigInteger()} ${it.price.toBigDecimal()} " }
            val buyOrder = OrderEntity.findBySequencerOrderId(it.buyGuid)
            val sellOrder = OrderEntity.findBySequencerOrderId(it.sellGuid)

            if (buyOrder != null && sellOrder != null) {
                val tradeEntity = TradeEntity.create(
                    timestamp = timestamp,
                    market = buyOrder.market,
                    amount = it.amount.toBigInteger(),
                    price = it.price.toBigDecimal(),
                )

                // create executions for both
                listOf(buyOrder, sellOrder).forEach { order ->
                    val execution = OrderExecutionEntity.create(
                        timestamp = timestamp,
                        orderEntity = order,
                        tradeEntity = tradeEntity,
                        role = if (order.type == OrderType.Market) ExecutionRole.Taker else ExecutionRole.Maker,
                        feeAmount = BigInteger.ZERO,
                        feeSymbol = Symbol(order.market.quoteSymbol.name),
                    )

                    execution.refresh(flush = true)
                    logger.debug { "Sending TradeCreated for order ${order.guid}" }
                    walletExecutionIds.add(order.wallet.address, execution.id.value)
                }

                // build the transaction to settle
                tradeEntity.toEip712Transaction()
            } else {
                null
            }
        }
        walletExecutionIds.forEach { (address, executionIds) ->
            broadcasterNotifications.add(address, BroadcasterNotification.TradesCreated(executionIds))
        }

        // update all orders that have changed
        val walletOrderIds: WalletOrders = mutableMapOf()
        response.ordersChangedList.forEach {
            if (ordersBeingUpdated.contains(it.guid.sequencerOrderId()) || it.disposition != OrderDisposition.Accepted) {
                logger.debug { "order updated for ${it.guid}, disposition ${it.disposition}" }
                OrderEntity.findBySequencerOrderId(it.guid)?.let { orderToUpdate ->
                    orderToUpdate.updateStatus(OrderStatus.fromOrderDisposition(it.disposition))
                    walletOrderIds.add(orderToUpdate.wallet.address, orderToUpdate.id.value)
                }
            }
        }
        walletOrderIds.forEach { (address, orderIds) ->
            broadcasterNotifications.add(address, BroadcasterNotification.OrdersUpdated(orderIds))
        }

        // update balance changes
        if (response.balancesChangedList.isNotEmpty()) {
            val walletMap =
                WalletEntity.getBySequencerIds(
                    response.balancesChangedList.map { SequencerWalletId(it.wallet) }
                        .toSet(),
                ).associateBy {
                    it.sequencerId.value
                }
            BalanceEntity.updateBalances(
                response.balancesChangedList.map { change ->
                    BalanceChange.Delta(
                        walletId = walletMap.getValue(change.wallet).guid.value,
                        symbolId = getSymbol(change.asset).guid.value,
                        amount = change.delta.toBigInteger(),
                    )
                },
                BalanceType.Available,
            )
            walletMap.values.forEach {
                broadcasterNotifications.add(it.address, BroadcasterNotification.Balances)
            }
        }

        // queue any blockchain txs for processing
        blockchainClient.queueTransactions(blockchainTxs)
        broadcasterNotifications.map { PrincipalNotifications(it.key, it.value) }.send()
    }

    private fun getSymbol(asset: String): SymbolEntity {
        return symbolMap.getOrPut(asset) {
            SymbolEntity.forChainAndName(blockchainClient.chainId, asset)
        }
    }

    private fun getMarket(marketId: MarketId): MarketEntity {
        return marketMap.getOrPut(marketId) {
            MarketEntity[marketId]
        }
    }

    private fun getSequencerOrderType(apiRequest: CreateOrderApiRequest): co.chainring.sequencer.proto.Order.Type {
        return when (apiRequest) {
            is CreateOrderApiRequest.Market ->
                when (apiRequest.side) {
                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.MarketBuy
                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.MarketSell
                }

            is CreateOrderApiRequest.Limit ->
                when (apiRequest.side) {
                    OrderSide.Buy -> co.chainring.sequencer.proto.Order.Type.LimitBuy
                    OrderSide.Sell -> co.chainring.sequencer.proto.Order.Type.LimitSell
                }
        }
    }

    private fun getContractAddress(asset: String): Address {
        return getSymbol(asset).contractAddress ?: Address.zero
    }

    private fun checkPrice(market: MarketEntity, price: BigDecimal?): BigDecimal? {
        if (price != null && BigDecimal.ZERO.compareTo(price.remainder(market.tickSize)) != 0) {
            throw ExchangeError("Order price is not a multiple of tick size")
        }
        return price
    }

    private fun checkIsOwner(ownedBy: Address, requestingAddress: Address) {
        if (ownedBy != requestingAddress) {
            throw ExchangeError("Order not created with this wallet")
        }
    }

    private fun checkMarket(left: MarketId, right: MarketId) {
        if (left != right) {
            throw ExchangeError("Markets are different")
        }
    }

    private fun validateOrderSignature(walletAddress: Address, marketId: MarketId, amount: BigInteger, side: OrderSide, price: BigDecimal?, nonce: String, signature: EvmSignature) {
        val (baseSymbol, quoteSymbol) = marketId.value.split("/")
        val tx = EIP712Transaction.Order(
            walletAddress,
            getContractAddress(baseSymbol),
            getContractAddress(quoteSymbol),
            if (side == OrderSide.Buy) amount else amount.negate(),
            price?.toFundamentalUnits(getSymbol(quoteSymbol).decimals) ?: BigInteger.ZERO,
            BigInteger(1, nonce.toHexBytes()),
            signature,
        )

        return blockchainClient.getContractAddress(ContractType.Exchange)?.let { verifyingContract ->
            if (!ECHelper.isValidSignature(
                    EIP712Helper.computeHash(
                        tx,
                        blockchainClient.chainId,
                        verifyingContract,
                    ),
                    tx.signature,
                    walletAddress,
                )
            ) {
                throw ExchangeError("Invalid signature")
            }
        } ?: throw ExchangeError("No deployed contract found")
    }

    override fun onTxConfirmation(tx: EIP712Transaction, error: String?) {
        transaction {
            val broadcasterNotifications: BroadcasterNotifications = mutableMapOf()
            when (tx) {
                is EIP712Transaction.WithdrawTx -> {
                    WithdrawalEntity.findPendingByWalletAndNonce(
                        WalletEntity.getByAddress(tx.sender)!!,
                        tx.nonce,
                    )?.let {
                        it.update(
                            status = error?.let { WithdrawalStatus.Failed }
                                ?: WithdrawalStatus.Complete,
                            error = error,
                        )
                        val finalBalance = runBlocking {
                            blockchainClient.getExchangeBalance(
                                it.wallet.address,
                                it.symbol.contractAddress ?: Address.zero,
                            )
                        }
                        BalanceEntity.updateBalances(
                            listOf(
                                BalanceChange.Replace(
                                    it.wallet.id.value,
                                    it.symbol.id.value,
                                    finalBalance,
                                ),
                            ),
                            BalanceType.Exchange,
                        )
                    }
                }

                is EIP712Transaction.Order -> {}

                is EIP712Transaction.Trade -> {
                    TradeEntity.findById(tx.tradeId)?.let { tradeEntity ->
                        if (error != null) {
                            BlockchainClient.logger.error { "settlement failed for ${tx.tradeId} - error is <$error>" }
                            tradeEntity.failSettlement()
                        } else {
                            BlockchainClient.logger.debug { "settlement completed for ${tx.tradeId}" }
                            tradeEntity.settle()
                        }

                        val executions = OrderExecutionEntity.findForTrade(tradeEntity)
                        // update the onchain balances
                        val wallets = executions.map { it.order.wallet }
                        val symbols = listOf(
                            executions.first().order.market.baseSymbol,
                            executions.first().order.market.quoteSymbol,
                        )
                        val finalExchangeBalances = runBlocking {
                            blockchainClient.getExchangeBalances(
                                wallets.map { it.address },
                                symbols.map { getContractAddress(it.name) },
                            )
                        }

                        BalanceEntity.updateBalances(
                            wallets.map { wallet ->
                                symbols.map { symbol ->
                                    BalanceChange.Replace(
                                        walletId = wallet.guid.value,
                                        symbolId = symbol.guid.value,
                                        amount = finalExchangeBalances.getValue(wallet.address).getValue(
                                            getContractAddress(
                                                symbol.name,
                                            ),
                                        ),
                                    )
                                }
                            }.flatten(),
                            BalanceType.Exchange,
                        )

                        executions.forEach { execution ->
                            broadcasterNotifications.add(execution.order.wallet.address, BroadcasterNotification.TradesUpdated(listOf(execution.id.value)))
                        }
                    }
                }
            }
            broadcasterNotifications.map { PrincipalNotifications(it.key, it.value) }.send()
        }
    }

    fun List<PrincipalNotifications>.send() {
        val payload = Json.encodeToString(this).also {
            logger.debug { "sending payload (${it.length} bytes) (value=$it) with notify" }
        }
        TransactionManager.current().notifyDbListener("broadcaster_ctl", payload)
    }

    override fun onExchangeContractDepositConfirmation(deposit: DepositEntity) {
        transaction {
            val response = runBlocking {
                sequencerClient.deposit(deposit.wallet.sequencerId.value, Asset(deposit.symbol.name), deposit.amount)
            }
            BalanceEntity.updateBalances(
                listOf(BalanceChange.Delta(deposit.wallet.id.value, deposit.symbol.guid.value, deposit.amount)),
                BalanceType.Exchange,
            )
            if (response.balancesChangedList.isEmpty()) {
                // Should never happen. Mark deposit as failed and wait for manual
                deposit.update(DepositStatus.Failed, "Rejected by sequencer")
            } else {
                handleSequencerResponse(response, mutableMapOf())
                deposit.update(DepositStatus.Complete)
            }
        }
    }
}
