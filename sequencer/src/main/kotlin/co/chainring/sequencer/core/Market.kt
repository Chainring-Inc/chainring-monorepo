package co.chainring.sequencer.core

import co.chainring.sequencer.proto.BalanceChange
import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderBatch
import co.chainring.sequencer.proto.OrderChangeRejected
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.balanceChange
import co.chainring.sequencer.proto.copy
import co.chainring.sequencer.proto.marketCheckpoint
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderChangeRejected
import co.chainring.sequencer.proto.orderChanged
import co.chainring.sequencer.proto.tradeCreated
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

// market price must be exactly halfway between two ticks
data class Market(
    val id: MarketId,
    val tickSize: BigDecimal,
    var marketPrice: BigDecimal,
    var bestBid: BigDecimal,
    var bestOffer: BigDecimal,
    val maxLevels: Int,
    val maxOrdersPerLevel: Int,
    val baseDecimals: Int,
    val quoteDecimals: Int,
    private var maxOfferIx: Int = -1,
    private var minBidIx: Int = -1,
) {
    private val halfTick = tickSize.setScale(tickSize.scale() + 1) / BigDecimal.valueOf(2)

    private fun marketIx(): Int =
        min(maxLevels / 2, (marketPrice - halfTick).divideToIntegralValue(tickSize).toInt())

    val levels: Array<OrderBookLevel> = marketIx().let { marketIx ->
        Array(maxLevels) { n ->
            if (n < marketIx) {
                OrderBookLevel(
                    n,
                    BookSide.Buy,
                    marketPrice.minus(tickSize.multiply((marketIx - n - 0.5).toBigDecimal())),
                    maxOrdersPerLevel,
                )
            } else {
                OrderBookLevel(
                    n,
                    BookSide.Sell,
                    marketPrice.plus(tickSize.multiply((n - marketIx + 0.5).toBigDecimal())),
                    maxOrdersPerLevel,
                )
            }
        }
    }

    // TODO - change these mutable maps to a HashMap that pre-allocates
    private val buyOrdersByWallet = mutableMapOf<WalletAddress, CopyOnWriteArrayList<LevelOrder>>()
    private val sellOrdersByWallet = mutableMapOf<WalletAddress, CopyOnWriteArrayList<LevelOrder>>()
    val ordersByGuid = mutableMapOf<OrderGuid, LevelOrder>()

    data class ConsumptionChange(
        val walletAddress: WalletAddress,
        val asset: Asset,
        val delta: BigInteger,
    )

    data class AddOrdersResult(
        val ordersChanged: List<OrderChanged>,
        val createdTrades: List<TradeCreated>,
        val balanceChanges: List<BalanceChange>,
        val consumptionChanges: List<ConsumptionChange>,
        val ordersChangeRejected: List<OrderChangeRejected>,
    )

    private fun sumBigIntegerPair(a: Pair<BigInteger, BigInteger>, b: Pair<BigInteger, BigInteger>) = Pair(a.first + b.first, a.second + b.second)

    fun applyOrderBatch(orderBatch: OrderBatch): AddOrdersResult {
        val ordersChanged = mutableListOf<OrderChanged>()
        val ordersChangeRejected = mutableListOf<OrderChangeRejected>()
        val createdTrades = mutableListOf<TradeCreated>()
        val balanceChanges = mutableMapOf<Pair<WalletAddress, Asset>, BigInteger>()
        val consumptionChanges = mutableMapOf<WalletAddress, Pair<BigInteger, BigInteger>>()
        orderBatch.ordersToCancelList.forEach { cancelOrder ->
            val validationResult = validateOrderForWallet(orderBatch.wallet, cancelOrder.guid)
            if (validationResult == OrderChangeRejected.Reason.None) {
                removeOrder(cancelOrder.guid.toOrderGuid())?.let { result ->
                    ordersChanged.add(
                        orderChanged {
                            this.guid = cancelOrder.guid
                            this.disposition = OrderDisposition.Canceled
                        },
                    )
                    consumptionChanges.merge(
                        result.wallet,
                        Pair(-result.baseAssetAmount, -result.quoteAssetAmount),
                        ::sumBigIntegerPair,
                    )
                }
            } else {
                ordersChangeRejected.add(
                    orderChangeRejected {
                        this.guid = cancelOrder.guid
                        this.reason = validationResult
                    },
                )
            }
        }
        orderBatch.ordersToChangeList.forEach { orderChange ->
            val validationResult = validateOrderForWallet(orderBatch.wallet, orderChange.guid)
            if (validationResult == OrderChangeRejected.Reason.None) {
                ordersByGuid[orderChange.guid.toOrderGuid()]?.let { order ->
                    val side = levels[order.levelIx].side
                    changeOrder(orderChange)?.let { changeOrderResult: ChangeOrderResult ->
                        ordersChanged.add(
                            orderChanged {
                                this.guid = orderChange.guid
                                this.disposition = changeOrderResult.disposition
                            },
                        )
                        consumptionChanges.merge(
                            changeOrderResult.wallet,
                            Pair(changeOrderResult.baseAssetDelta, changeOrderResult.quoteAssetDelta),
                            ::sumBigIntegerPair,
                        )
                        changeOrderResult.executions.forEach { execution ->
                            processExecution(
                                wallet = orderBatch.wallet.toWalletAddress(),
                                order = order {
                                    this.guid = orderChange.guid
                                    // type is not available on orderChange, therefore resolving from the level
                                    this.type = if (side == BookSide.Buy) Order.Type.LimitBuy else Order.Type.LimitSell
                                    this.amount = orderChange.amount
                                    this.price = orderChange.price
                                },
                                execution = execution,
                                createdTrades = createdTrades,
                                ordersChanged = ordersChanged,
                                balanceChanges = balanceChanges,
                                consumptionChanges = consumptionChanges,
                            )
                        }
                    }
                }
            } else {
                ordersChangeRejected.add(
                    orderChangeRejected {
                        this.guid = orderChange.guid
                        this.reason = validationResult
                    },
                )
            }
        }
        orderBatch.ordersToAddList.forEach { order ->
            val orderResult = addOrder(orderBatch.wallet, order)
            ordersChanged.add(
                orderChanged {
                    this.guid = order.guid
                    this.disposition = orderResult.disposition
                },
            )
            if (orderResult.disposition == OrderDisposition.Accepted || orderResult.disposition == OrderDisposition.PartiallyFilled) {
                // immediately filled limit order's amount should not count to consumption
                val filledAmount = orderResult.executions.sumOf { it.amount }
                when (order.type) {
                    Order.Type.LimitBuy -> consumptionChanges.merge(
                        orderBatch.wallet.toWalletAddress(),
                        Pair(
                            BigInteger.ZERO,
                            notional(order.amount.toBigInteger() - filledAmount, order.price.toBigDecimal(), baseDecimals, quoteDecimals),
                        ),
                        ::sumBigIntegerPair,
                    )

                    Order.Type.LimitSell -> consumptionChanges.merge(
                        orderBatch.wallet.toWalletAddress(),
                        Pair(order.amount.toBigInteger() - filledAmount, BigInteger.ZERO),
                        ::sumBigIntegerPair,
                    )

                    else -> {}
                }
            }
            orderResult.executions.forEach { execution ->
                processExecution(
                    wallet = orderBatch.wallet.toWalletAddress(),
                    order = order,
                    execution = execution,
                    createdTrades = createdTrades,
                    ordersChanged = ordersChanged,
                    balanceChanges = balanceChanges,
                    consumptionChanges = consumptionChanges,
                )
            }
        }
        return AddOrdersResult(
            ordersChanged,
            createdTrades,
            balanceChanges.mapNotNull { (k, delta) ->
                if (delta != BigInteger.ZERO) {
                    val (wallet, asset) = k
                    balanceChange {
                        this.wallet = wallet.value
                        this.asset = asset.value
                        this.delta = delta.toIntegerValue()
                    }
                } else {
                    null
                }
            },
            consumptionChanges.flatMap {
                listOf(
                    ConsumptionChange(
                        walletAddress = it.key,
                        asset = this.id.baseAsset(),
                        delta = it.value.first,
                    ),
                    ConsumptionChange(
                        walletAddress = it.key,
                        asset = this.id.quoteAsset(),
                        delta = it.value.second,
                    ),
                )
            },
            ordersChangeRejected,
        )
    }

    private fun processExecution(
        wallet: WalletAddress,
        order: Order,
        execution: Execution,
        createdTrades: MutableList<TradeCreated>,
        ordersChanged: MutableList<OrderChanged>,
        balanceChanges: MutableMap<Pair<WalletAddress, Asset>, BigInteger>,
        consumptionChanges: MutableMap<WalletAddress, Pair<BigInteger, BigInteger>>,
    ) {
        createdTrades.add(
            tradeCreated {
                if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) {
                    buyGuid = order.guid
                    sellGuid = execution.counterOrder.guid.value
                } else {
                    buyGuid = execution.counterOrder.guid.value
                    sellGuid = order.guid
                }
                amount = execution.amount.toIntegerValue()
                price = execution.price.toDecimalValue()
            },
        )
        ordersChanged.add(
            orderChanged {
                this.guid = execution.counterOrder.guid.value
                if (execution.counterOrderExhausted) {
                    this.disposition = OrderDisposition.Filled
                } else {
                    this.disposition = OrderDisposition.PartiallyFilled
                    this.newQuantity = execution.counterOrder.quantity.toIntegerValue()
                }
            },
        )
        val notional = notional(execution.amount, execution.price, baseDecimals, quoteDecimals)
        val base = id.baseAsset()
        val quote = id.quoteAsset()
        val (buyer, seller) = if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) wallet to execution.counterOrder.wallet else execution.counterOrder.wallet to wallet
        balanceChanges.merge(Pair(buyer, quote), -notional, ::sumBigIntegers)
        balanceChanges.merge(Pair(seller, base), -execution.amount, ::sumBigIntegers)
        balanceChanges.merge(Pair(buyer, base), execution.amount, ::sumBigIntegers)
        balanceChanges.merge(Pair(seller, quote), notional, ::sumBigIntegers)
        if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) {
            consumptionChanges.merge(execution.counterOrder.wallet, Pair(-execution.amount, BigInteger.ZERO), ::sumBigIntegerPair)
        } else {
            consumptionChanges.merge(execution.counterOrder.wallet, Pair(BigInteger.ZERO, -notional), ::sumBigIntegerPair)
        }
    }

    fun autoReduce(walletAddress: WalletAddress, asset: Asset, limit: BigInteger): List<OrderChanged> {
        var total = BigInteger.ZERO
        return if (asset == id.baseAsset()) {
            sellOrdersByWallet[walletAddress]?.let { sellOrders ->
                sellOrders.sortedBy { it.levelIx }.mapNotNull { levelOrder ->
                    if (levelOrder.quantity <= limit - total) {
                        total += levelOrder.quantity
                        null
                    } else {
                        levelOrder.quantity = limit - total
                        total += levelOrder.quantity
                        orderChanged {
                            this.guid = levelOrder.guid.value
                            this.disposition = OrderDisposition.AutoReduced
                            this.newQuantity = levelOrder.quantity.toIntegerValue()
                        }
                    }
                }
            } ?: emptyList()
        } else {
            buyOrdersByWallet[walletAddress]?.let { buyOrders ->
                buyOrders.sortedByDescending { it.levelIx }.mapNotNull { levelOrder ->
                    val price = levels[levelOrder.levelIx].price
                    val notionalAmount = notional(levelOrder.quantity, price, baseDecimals, quoteDecimals)
                    if (notionalAmount + total <= limit) {
                        total += notionalAmount
                        null
                    } else {
                        // invert the notional calculation using the remaining notional amount
                        val remainingNotional = (limit - total)
                        levelOrder.quantity = (remainingNotional.toBigDecimal() / price).movePointRight(baseDecimals - quoteDecimals).toBigInteger()
                        total += remainingNotional
                        orderChanged {
                            this.guid = levelOrder.guid.value
                            this.disposition = OrderDisposition.AutoReduced
                            this.newQuantity = levelOrder.quantity.toIntegerValue()
                        }
                    }
                }
            } ?: emptyList()
        }
    }

    fun baseAssetsRequired(wallet: WalletAddress): BigInteger = sellOrdersByWallet[wallet]?.map { it.quantity }?.reduceOrNull(::sumBigIntegers) ?: BigInteger.ZERO

    fun quoteAssetsRequired(wallet: WalletAddress): BigInteger = buyOrdersByWallet[wallet]?.map {
        notional(it.quantity, levels[it.levelIx].price, baseDecimals, quoteDecimals)
    }?.reduceOrNull(::sumBigIntegers) ?: BigInteger.ZERO

    private fun handleMarketOrder(order: Order, stopAtLevelIx: Int? = null): AddOrderResult {
        val originalAmount = order.amount.toBigInteger()
        var remainingAmount = originalAmount
        val executions = mutableListOf<Execution>()
        val maxBidIx = (bestBid - levels[0].price).divideToIntegralValue(tickSize).toInt()
        val minOfferIx = (bestOffer - levels[0].price).divideToIntegralValue(tickSize).toInt()
        var index = if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) {
            minOfferIx
        } else {
            maxBidIx
        }

        while (index >= 0 && index <= levels.size) {
            val orderBookLevel = levels[index]
            if (stopAtLevelIx != null) {
                // stopAtLevelIx is provided to handle crossing-market execution of limit orders
                if (
                    order.type == Order.Type.LimitBuy && orderBookLevel.levelIx > stopAtLevelIx ||
                    order.type == Order.Type.LimitSell && orderBookLevel.levelIx < stopAtLevelIx
                ) {
                    break
                }
            }
            val orderBookLevelFill = orderBookLevel.fillOrder(remainingAmount)
            remainingAmount = orderBookLevelFill.remainingAmount
            executions.addAll(orderBookLevelFill.executions)
            if (remainingAmount == BigInteger.ZERO) {
                break
            }
            if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) {
                index += 1
                if (index > maxOfferIx) {
                    break
                }
            } else {
                index -= 1
                if (index < minBidIx) {
                    break
                }
            }
        }
        return if (remainingAmount < originalAmount) {
            // adjust market price to midpoint
            marketPrice = if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) {
                executions.lastOrNull()?.let { lastExecution ->
                    val lastExecutionLevel = levels[lastExecution.counterOrder.levelIx]
                    bestOffer = if (lastExecutionLevel.totalQuantity > BigInteger.ZERO) {
                        executions.last().price
                    } else {
                        levels[min(maxOfferIx, lastExecutionLevel.levelIx + 1)].price
                    }
                }
                ((levels[min(index, maxLevels - 1)].price) + levels[maxBidIx].price).setScale(marketPrice.scale()) / BigDecimal.valueOf(2)
            } else {
                executions.lastOrNull()?.let { lastExecution ->
                    val lastExecutionLevel = levels[lastExecution.counterOrder.levelIx]
                    bestBid = if (lastExecutionLevel.totalQuantity > BigInteger.ZERO) {
                        executions.last().price
                    } else {
                        levels[max(minBidIx, lastExecutionLevel.levelIx - 1)].price
                    }
                }
                ((levels[max(index, 0)].price) + levels[minOfferIx].price).setScale(marketPrice.scale()) / BigDecimal.valueOf(2)
            }

            // remove from buy/sell
            executions.forEach {
                if (it.counterOrderExhausted) {
                    (if (order.type == Order.Type.MarketBuy || order.type == Order.Type.LimitBuy) sellOrdersByWallet else buyOrdersByWallet)[it.counterOrder.wallet]?.remove(it.counterOrder)
                    ordersByGuid.remove(it.counterOrder.guid)
                }
            }

            if (remainingAmount > BigInteger.ZERO) {
                AddOrderResult(OrderDisposition.PartiallyFilled, executions)
            } else {
                AddOrderResult(OrderDisposition.Filled, executions)
            }
        } else {
            AddOrderResult(OrderDisposition.Rejected, noExecutions)
        }
    }

    // if the order is found, returns wallet and how much of the base asset and quote asset it was consuming; null otherwise
    private fun removeOrder(guid: OrderGuid): RemoveOrderResult? {
        var ret: RemoveOrderResult? = null
        ordersByGuid[guid]?.let { levelOrder ->
            val level = levels[levelOrder.levelIx]
            ret = if (level.side == BookSide.Buy) {
                buyOrdersByWallet[levelOrder.wallet]?.remove(levelOrder)
                RemoveOrderResult(levelOrder.wallet, BigInteger.ZERO, notional(levelOrder.quantity, level.price, baseDecimals, quoteDecimals))
            } else {
                sellOrdersByWallet[levelOrder.wallet]?.remove(levelOrder)
                RemoveOrderResult(levelOrder.wallet, levelOrder.quantity, BigInteger.ZERO)
            }
            level.removeLevelOrder(levelOrder)
            ordersByGuid.remove(guid)
        }
        return ret
    }

    fun addOrder(wallet: Long, order: Order): AddOrderResult {
        return if (order.type == Order.Type.LimitSell) {
            val orderPrice = order.price.toBigDecimal()
            val levelIx = (orderPrice - levels[0].price).divideToIntegralValue(tickSize).toInt()
            if (levelIx > levels.lastIndex) {
                AddOrderResult(OrderDisposition.Rejected, noExecutions)
            } else {
                if (levelIx > maxOfferIx) {
                    maxOfferIx = levelIx
                }

                if (orderPrice <= bestBid) {
                    // in case when crossing market execute as market sell order until `levelIx`
                    val marketOrderResult = handleMarketOrder(order, stopAtLevelIx = levelIx)
                    val filledAmount = marketOrderResult.executions.sumOf { it.amount }
                    val remainingAmount = order.amount.toBigInteger() - filledAmount

                    if (remainingAmount > BigInteger.ZERO) {
                        // and then create limit order for the remaining amount
                        val adjustedOrder = order.copy { amount = remainingAmount.toIntegerValue() }
                        val limitOrderDisposition = createLimitSellOrder(levelIx, wallet, adjustedOrder)

                        AddOrderResult(
                            // market part of the limit order can be rejected due to no liquidity yet in the empty market
                            // this happens because `bestBid` set to half tick away from the market price during market creation
                            // potentially can be solved by widening initial bestBid/bestOffer distance (e.g. outer boundaries of the market)
                            if (marketOrderResult.disposition == OrderDisposition.Rejected) limitOrderDisposition else marketOrderResult.disposition,
                            marketOrderResult.executions,
                        )
                    } else {
                        AddOrderResult(marketOrderResult.disposition, marketOrderResult.executions)
                    }
                } else {
                    // or just create a limit order
                    val disposition = createLimitSellOrder(levelIx, wallet, order)
                    AddOrderResult(disposition, noExecutions)
                }
            }
        } else if (order.type == Order.Type.LimitBuy) {
            val orderPrice = order.price.toBigDecimal()
            val levelIx = (orderPrice - levels[0].price).divideToIntegralValue(tickSize).toInt()
            if (levelIx < 0) {
                AddOrderResult(OrderDisposition.Rejected, noExecutions)
            } else {
                if (levelIx < minBidIx || minBidIx == -1) {
                    minBidIx = levelIx
                }

                if (orderPrice >= bestOffer) {
                    // in case when crossing market execute as market buy order until `levelIx`
                    val marketOrderResult = handleMarketOrder(order, stopAtLevelIx = levelIx)
                    val filledAmount = marketOrderResult.executions.sumOf { it.amount }
                    val remainingAmount = order.amount.toBigInteger() - filledAmount

                    if (remainingAmount > BigInteger.ZERO) {
                        // and then create limit order for the remaining amount
                        val adjustedOrder = order.copy { amount = remainingAmount.toIntegerValue() }
                        val limitOrderDisposition = createLimitBuyOrder(levelIx, wallet, adjustedOrder)

                        AddOrderResult(
                            // market part of the limit order can be rejected due to no liquidity yet in the empty market
                            // this happens because `bestOffer` set to half tick away from the market price during market creation
                            // potentially can be solved by widening initial bestBid/bestOffer distance (e.g. outer boundaries of the market)
                            if (marketOrderResult.disposition == OrderDisposition.Rejected) limitOrderDisposition else marketOrderResult.disposition,
                            marketOrderResult.executions,
                        )
                    } else {
                        AddOrderResult(marketOrderResult.disposition, marketOrderResult.executions)
                    }
                } else {
                    // or just create a limit order
                    val disposition = createLimitBuyOrder(levelIx, wallet, order)
                    AddOrderResult(disposition, noExecutions)
                }
            }
        } else if (order.type == Order.Type.MarketBuy) {
            handleMarketOrder(order)
        } else if (order.type == Order.Type.MarketSell) {
            handleMarketOrder(order)
        } else {
            AddOrderResult(OrderDisposition.Rejected, noExecutions)
        }
    }

    private fun createLimitBuyOrder(levelIx: Int, wallet: Long, order: Order): OrderDisposition {
        val orderPrice = order.price.toBigDecimal()
        val (disposition, levelOrder) = levels[levelIx].addOrder(wallet, order)
        if (disposition == OrderDisposition.Accepted) {
            buyOrdersByWallet.getOrPut(levelOrder!!.wallet) { CopyOnWriteArrayList() }.add(levelOrder)
            ordersByGuid[levelOrder.guid] = levelOrder
            levels[levelIx].side = BookSide.Buy
            if (orderPrice > bestBid) {
                bestBid = orderPrice
            }
        }
        return disposition
    }

    private fun createLimitSellOrder(levelIx: Int, wallet: Long, order: Order): OrderDisposition {
        val orderPrice = order.price.toBigDecimal()
        val (disposition, levelOrder) = levels[levelIx].addOrder(wallet, order)
        if (disposition == OrderDisposition.Accepted) {
            sellOrdersByWallet.getOrPut(levelOrder!!.wallet) { CopyOnWriteArrayList() }.add(levelOrder)
            ordersByGuid[levelOrder.guid] = levelOrder
            levels[levelIx].side = BookSide.Sell
            if (orderPrice < bestOffer) {
                bestOffer = orderPrice
            }
        }
        return disposition
    }

    // calculate how much liquidity is available for a market buy (until stopAtLevelIx), and what the final clearing price would be
    fun clearingPriceAndQuantityForMarketBuy(amount: BigInteger, stopAtLevelIx: Int? = null): Pair<BigDecimal, BigInteger> {
        var index = (bestOffer - levels[0].price).divideToIntegralValue(tickSize).toInt()

        var remainingAmount = amount
        var totalPriceUnits = BigDecimal.ZERO

        while (index <= levels.size) {
            if (stopAtLevelIx != null && index > stopAtLevelIx) {
                // stopAtLevelIx is provided to handle crossing-market execution of limit orders
                break
            }
            val quantityAtLevel = levels[index].totalQuantity.min(remainingAmount)
            totalPriceUnits += quantityAtLevel.toBigDecimal() * levels[index].price
            remainingAmount -= quantityAtLevel
            if (remainingAmount == BigInteger.ZERO) {
                break
            }
            index += 1
            if (index > maxOfferIx) {
                break
            }
        }
        val availableQuantity = amount - remainingAmount
        return Pair(if (availableQuantity == BigInteger.ZERO) BigDecimal.ZERO else totalPriceUnits / availableQuantity.toBigDecimal(), availableQuantity)
    }

    // calculate how much liquidity is available for a market sell order (until stopAtLevelIx)
    fun clearingQuantityForMarketSell(amount: BigInteger, stopAtLevelIx: Int? = null): BigInteger {
        var index = (bestBid - levels[0].price).divideToIntegralValue(tickSize).toInt()

        var remainingAmount = amount

        while (index <= levels.size) {
            if (stopAtLevelIx != null && index < stopAtLevelIx) {
                // stopAtLevelIx is provided to handle crossing-market execution of limit orders
                break
            }
            val quantityAtLevel = levels[index].totalQuantity.min(remainingAmount)
            remainingAmount -= quantityAtLevel
            if (remainingAmount == BigInteger.ZERO) {
                break
            }
            index -= 1
            if (index < minBidIx) {
                break
            }
        }
        return amount - remainingAmount
    }

    // returns baseAsset and quoteAsset reserved by order
    fun assetsReservedForOrder(levelOrder: LevelOrder): Pair<BigInteger, BigInteger> {
        val level = levels[levelOrder.levelIx]
        return if (level.side == BookSide.Buy) {
            BigInteger.ZERO to notional(levelOrder.quantity, level.price, baseDecimals, quoteDecimals)
        } else {
            levelOrder.quantity to BigInteger.ZERO
        }
    }

    // this will change an order's price and quantity.
    // if the price change would cross the market order will be filled (partially)
    private fun changeOrder(orderChange: Order): ChangeOrderResult? {
        return ordersByGuid[orderChange.guid.toOrderGuid()]?.let { order ->
            val wallet = order.wallet
            val level = levels[order.levelIx]
            val newPrice = orderChange.price.toBigDecimal()
            val newQuantity = orderChange.amount.toBigInteger()
            val quantityDelta = newQuantity - order.quantity
            if (newPrice.compareTo(level.price) == 0) {
                // price stays same, quantity changes
                val baseAssetDelta = if (level.side == BookSide.Buy) BigInteger.ZERO else quantityDelta
                val quoteAssetDelta = if (level.side == BookSide.Buy) notional(quantityDelta, level.price, baseDecimals, quoteDecimals) else BigInteger.ZERO
                level.totalQuantity += quantityDelta
                order.quantity = newQuantity
                ChangeOrderResult(order.wallet, OrderDisposition.Accepted, noExecutions, baseAssetDelta, quoteAssetDelta)
            } else {
                // price change results into deleting existing and re-adding new order
                val (baseAssetDelta, quoteAssetDelta) = if (level.side == BookSide.Buy) {
                    val previousNotional = notional(order.quantity, levels[order.levelIx].price, baseDecimals, quoteDecimals)

                    val notionalDelta = if (newPrice >= bestOffer) {
                        // with the updated price limit order crosses the market
                        val levelIx = levelIx(newPrice)
                        val (_, availableQuantity) = clearingPriceAndQuantityForMarketBuy(orderChange.amount.toBigInteger(), stopAtLevelIx = levelIx)
                        val remainingQuantity = orderChange.amount.toBigInteger() - availableQuantity

                        // traded on crossing market notional chuck should be excluded from quoteAssetDelta
                        val limitChunkNotional = notional(remainingQuantity, newPrice, baseDecimals, quoteDecimals)

                        limitChunkNotional - previousNotional
                    } else {
                        val orderChangeNotional = notional(newQuantity, newPrice, baseDecimals, quoteDecimals)
                        orderChangeNotional - previousNotional
                    }

                    // update bestBid only when order stays on the book
                    if (newPrice > bestBid && (previousNotional + notionalDelta) > BigInteger.ZERO) bestBid = newPrice

                    BigInteger.ZERO to notionalDelta
                } else {
                    val levelIx = levelIx(newPrice)
                    val marketChunkQuantity = clearingQuantityForMarketSell(newQuantity, stopAtLevelIx = levelIx)
                    val limitChunkQuantity = order.quantity - marketChunkQuantity

                    // update bestOffer only when order stays on the book
                    if (newPrice < bestOffer && limitChunkQuantity > BigInteger.ZERO) bestOffer = newPrice

                    marketChunkQuantity - order.quantity to BigInteger.ZERO
                }
                removeOrder(order.guid)
                val addOrderResult = addOrder(
                    // note: order object is reset during removal, 'order.wallet' will return 0
                    wallet.value,
                    order {
                        this.guid = orderChange.guid
                        this.type = if (level.side == BookSide.Buy) Order.Type.LimitBuy else Order.Type.LimitSell
                        this.amount = orderChange.amount
                        this.price = orderChange.price
                    },
                )
                ChangeOrderResult(wallet, addOrderResult.disposition, addOrderResult.executions, baseAssetDelta, quoteAssetDelta)
            }
        }
    }
    private fun validateOrderForWallet(wallet: Long, orderGuid: Long): OrderChangeRejected.Reason {
        return ordersByGuid[orderGuid.toOrderGuid()]?.let { order ->
            if (wallet == order.wallet.value) {
                OrderChangeRejected.Reason.None
            } else {
                OrderChangeRejected.Reason.NotForWallet
            }
        } ?: OrderChangeRejected.Reason.DoesNotExist
    }

    fun levelIx(price: BigDecimal): Int {
        return (price - levels[0].price).divideToIntegralValue(tickSize).toInt()
    }

    // equals and hashCode are overridden because of levels are stored in array
    // see https://kotlinlang.org/docs/arrays.html#compare-arrays
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Market

        if (maxLevels != other.maxLevels) return false
        if (maxOrdersPerLevel != other.maxOrdersPerLevel) return false
        if (tickSize != other.tickSize) return false
        if (marketPrice != other.marketPrice) return false
        if (baseDecimals != other.baseDecimals) return false
        if (quoteDecimals != other.quoteDecimals) return false
        if (maxOfferIx != other.maxOfferIx) return false
        if (minBidIx != other.minBidIx) return false
        if (halfTick != other.halfTick) return false
        if (!levels.contentEquals(other.levels)) return false
        if (buyOrdersByWallet != other.buyOrdersByWallet) return false
        if (sellOrdersByWallet != other.sellOrdersByWallet) return false
        if (bestBid != other.bestBid) return false
        if (bestOffer != other.bestOffer) return false
        return ordersByGuid == other.ordersByGuid
    }

    override fun hashCode(): Int {
        var result = maxLevels
        result = 31 * result + maxOrdersPerLevel
        result = 31 * result + tickSize.hashCode()
        result = 31 * result + marketPrice.hashCode()
        result = 31 * result + baseDecimals
        result = 31 * result + quoteDecimals
        result = 31 * result + maxOfferIx
        result = 31 * result + minBidIx
        result = 31 * result + halfTick.hashCode()
        result = 31 * result + levels.contentHashCode()
        result = 31 * result + buyOrdersByWallet.hashCode()
        result = 31 * result + sellOrdersByWallet.hashCode()
        result = 31 * result + bestBid.hashCode()
        result = 31 * result + bestOffer.hashCode()
        result = 31 * result + ordersByGuid.hashCode()
        return result
    }

    fun toCheckpoint(): MarketCheckpoint {
        return marketCheckpoint {
            this.id = this@Market.id.value
            this.tickSize = this@Market.tickSize.toDecimalValue()
            this.marketPrice = this@Market.marketPrice.toDecimalValue()
            this.maxLevels = this@Market.maxLevels
            this.maxOrdersPerLevel = this@Market.maxOrdersPerLevel
            this.baseDecimals = this@Market.baseDecimals
            this.quoteDecimals = this@Market.quoteDecimals
            this.minBidIx = this@Market.minBidIx
            this.maxOfferIx = this@Market.maxOfferIx
            this.bestBid = this@Market.bestBid.toDecimalValue()
            this.bestOffer = this@Market.bestOffer.toDecimalValue()
            val marketIx = marketIx()
            val firstLevelWithData = this.minBidIx.let { if (it == -1) marketIx else it }
            val lastLevelWithData = this.maxOfferIx.let { if (it == -1) marketIx else it }
            (firstLevelWithData..lastLevelWithData).forEach { i ->
                this.levels.add(this@Market.levels[i].toCheckpoint())
            }
        }
    }

    companion object {
        fun fromCheckpoint(checkpoint: MarketCheckpoint): Market {
            val tickSize = checkpoint.tickSize.toBigDecimal()
            return Market(
                id = checkpoint.id.toMarketId(),
                tickSize = tickSize,
                marketPrice = checkpoint.marketPrice.toBigDecimal(),
                maxLevels = checkpoint.maxLevels,
                maxOrdersPerLevel = checkpoint.maxOrdersPerLevel,
                baseDecimals = checkpoint.baseDecimals,
                quoteDecimals = checkpoint.quoteDecimals,
                minBidIx = checkpoint.minBidIx,
                maxOfferIx = checkpoint.maxOfferIx,
                bestBid = checkpoint.bestBid?.toBigDecimal()
                    ?: (
                        checkpoint.marketPrice.toBigDecimal() - tickSize.setScale(tickSize.scale() + 1)
                            .divide(BigDecimal.valueOf(2))
                        ),
                bestOffer = checkpoint.bestOffer?.toBigDecimal()
                    ?: (
                        checkpoint.marketPrice.toBigDecimal() + tickSize.setScale(tickSize.scale() + 1)
                            .divide(
                                BigDecimal.valueOf(2),
                            )
                        ),
            ).apply {
                checkpoint.levelsList.forEach { levelCheckpoint ->
                    this.levels[levelCheckpoint.levelIx].fromCheckpoint(levelCheckpoint)
                }

                levels.forEach { level ->
                    (level.orderHead.until(level.orderTail)).forEach { i ->
                        val order = level.orders[i]
                        this.ordersByGuid[order.guid] = order

                        when (level.side) {
                            BookSide.Buy -> buyOrdersByWallet
                            BookSide.Sell -> sellOrdersByWallet
                        }.apply {
                            getOrPut(order.wallet) { CopyOnWriteArrayList<LevelOrder>() }.add(order)
                        }
                    }
                }
            }
        }
    }
}
