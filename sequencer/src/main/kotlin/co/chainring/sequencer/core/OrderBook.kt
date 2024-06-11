package co.chainring.sequencer.core

import co.chainring.sequencer.proto.MarketCheckpoint
import co.chainring.sequencer.proto.MarketCheckpointKt.levelOrder
import co.chainring.sequencer.proto.MarketCheckpointKt.orderBookLevel
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderDisposition
import java.math.BigDecimal
import java.math.BigInteger

enum class BookSide {
    Buy,
    Sell,
}

val noExecutions = listOf<Execution>()

data class OrderBookLevelFill(
    val remainingAmount: BigInteger,
    val executions: List<Execution>,
)

data class LevelOrder(
    var guid: OrderGuid,
    var wallet: WalletAddress,
    var quantity: BigInteger,
    var feeRate: FeeRate,
    var levelIx: Int,
    var originalQuantity: BigInteger = quantity,
) {
    fun update(wallet: Long, order: Order, feeRate: FeeRate) {
        this.guid = order.guid.toOrderGuid()
        this.wallet = wallet.toWalletAddress()
        this.quantity = order.amount.toBigInteger()
        this.originalQuantity = this.quantity
        this.feeRate = feeRate
    }

    fun reset() {
        this.guid = OrderGuid.none
        this.wallet = WalletAddress.none
        this.quantity = BigInteger.ZERO
        this.feeRate = FeeRate.zero
        this.originalQuantity = this.quantity
    }

    fun toCheckpoint(): MarketCheckpoint.LevelOrder {
        return levelOrder {
            this.guid = this@LevelOrder.guid.value
            this.wallet = this@LevelOrder.wallet.value
            this.quantity = this@LevelOrder.quantity.toIntegerValue()
            this.levelIx = this@LevelOrder.levelIx
            this.originalQuantity = this@LevelOrder.originalQuantity.toIntegerValue()
            this.feeRate = this@LevelOrder.feeRate.value
        }
    }

    fun fromCheckpoint(checkpoint: MarketCheckpoint.LevelOrder) {
        this.guid = OrderGuid(checkpoint.guid)
        this.wallet = WalletAddress(checkpoint.wallet)
        this.quantity = checkpoint.quantity.toBigInteger()
        this.levelIx = checkpoint.levelIx
        this.originalQuantity = checkpoint.originalQuantity.toBigInteger()
        this.feeRate = FeeRate(checkpoint.feeRate)
    }
}

class OrderBookLevel(val levelIx: Int, var side: BookSide, val price: BigDecimal, val maxOrderCount: Int) {
    val orders = Array(maxOrderCount) { _ ->
        LevelOrder(guid = 0L.toOrderGuid(), wallet = 0L.toWalletAddress(), quantity = BigInteger.ZERO, feeRate = FeeRate.zero, levelIx = levelIx)
    }
    var totalQuantity = BigInteger.ZERO
    var orderHead = 0
    var orderTail = 0

    fun toCheckpoint(): MarketCheckpoint.OrderBookLevel {
        return orderBookLevel {
            this.levelIx = this@OrderBookLevel.levelIx
            this.side = when (this@OrderBookLevel.side) {
                BookSide.Buy -> MarketCheckpoint.BookSide.Buy
                BookSide.Sell -> MarketCheckpoint.BookSide.Sell
            }
            this.price = this@OrderBookLevel.price.toDecimalValue()
            this.maxOrderCount = this@OrderBookLevel.maxOrderCount
            this.totalQuantity = this@OrderBookLevel.totalQuantity.toIntegerValue()
            this.orderHead = this@OrderBookLevel.orderHead
            this.orderTail = this@OrderBookLevel.orderTail

            // store orders respecting level's circular buffer
            var currentIndex = this@OrderBookLevel.orderHead
            while (currentIndex != this@OrderBookLevel.orderTail) {
                val order = this@OrderBookLevel.orders[currentIndex]
                this.orders.add(order.toCheckpoint())
                currentIndex = (currentIndex + 1) % this@OrderBookLevel.maxOrderCount
            }
        }
    }

    fun fromCheckpoint(checkpoint: MarketCheckpoint.OrderBookLevel) {
        orderHead = checkpoint.orderHead
        orderTail = checkpoint.orderTail
        side = when (checkpoint.side) {
            MarketCheckpoint.BookSide.Buy -> BookSide.Buy
            MarketCheckpoint.BookSide.Sell -> BookSide.Sell
            else -> throw IllegalStateException("Unexpected level book side '${checkpoint.side}'")
        }

        // restore orders respecting level's circular buffer
        val checkpointOrdersCount = checkpoint.ordersList.size
        for (i in 0 until checkpointOrdersCount) {
            val orderCheckpoint = checkpoint.ordersList[i]
            val index = (orderHead + i) % maxOrderCount
            orders[index].fromCheckpoint(orderCheckpoint)
        }
        totalQuantity = checkpoint.totalQuantity.toBigInteger()
    }

    fun addOrder(wallet: Long, order: Order, feeRate: FeeRate): Pair<OrderDisposition, LevelOrder?> {
        val nextTail = (orderTail + 1) % maxOrderCount
        return if (nextTail == orderHead) {
            OrderDisposition.Rejected to null
        } else {
            val levelOrder = orders[orderTail]
            levelOrder.update(wallet, order, feeRate)
            totalQuantity += levelOrder.quantity
            orderTail = nextTail
            OrderDisposition.Accepted to levelOrder
        }
    }

    fun fillOrder(requestedAmount: BigInteger): OrderBookLevelFill {
        var ix = orderHead
        val executions = mutableListOf<Execution>()
        var remainingAmount = requestedAmount
        while (ix != orderTail && remainingAmount > BigInteger.ZERO) {
            val curOrder = orders[ix]
            if (remainingAmount >= curOrder.quantity) {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = curOrder.quantity,
                        price = this.price,
                        counterOrderExhausted = true,
                    ),
                )
                totalQuantity -= curOrder.quantity
                remainingAmount -= curOrder.quantity
                ix = (ix + 1) % maxOrderCount
            } else {
                executions.add(
                    Execution(
                        counterOrder = curOrder,
                        amount = remainingAmount,
                        price = this.price,
                        counterOrderExhausted = false,
                    ),
                )
                totalQuantity -= remainingAmount
                curOrder.quantity -= remainingAmount
                remainingAmount = BigInteger.ZERO
            }
        }
        // remove consumed orders
        orderHead = ix // TODO: CHAIN-274 Also reset consumed orders

        return OrderBookLevelFill(
            remainingAmount,
            executions,
        )
    }

    fun removeLevelOrder(levelOrder: LevelOrder) {
        val orderIx = orders.indexOf(levelOrder)
        totalQuantity -= levelOrder.quantity
        levelOrder.reset()
        if (orderIx == (orderTail - 1 + maxOrderCount) % maxOrderCount) {
            orderTail = (orderTail - 1 + maxOrderCount) % maxOrderCount
        } else if (orderIx < orderHead) {
            // copy from after orderIx to orderTail, and decrement orderTail
            if (orderIx < orderTail) {
                val orderIxRef = orders[orderIx]
                System.arraycopy(orders, orderIx + 1, orders, orderIx, orderTail - orderIx)
                orders[orderTail] = orderIxRef
            }
            orderTail = (orderTail - 1 + maxOrderCount) % maxOrderCount
        } else {
            if (orderIx > orderHead) {
                val orderIxRef = orders[orderIx]
                System.arraycopy(orders, orderHead, orders, orderHead + 1, orderIx - orderHead)
                orders[orderHead] = orderIxRef
            }
            orderHead = (orderHead + 1) % maxOrderCount
        }
    }

    // equals and hashCode are overridden because of orders are stored in array
    // see https://kotlinlang.org/docs/arrays.html#compare-arrays
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OrderBookLevel

        if (levelIx != other.levelIx) return false
        if (side != other.side) return false
        if (price != other.price) return false
        if (maxOrderCount != other.maxOrderCount) return false
        if (totalQuantity != other.totalQuantity) return false

        // Compare orders between orderHead and orderTail
        var thisOrderIndex = this.orderHead
        var otherOrderIndex = other.orderHead

        while (thisOrderIndex != this.orderTail && otherOrderIndex != other.orderTail) {
            if (this.orders[thisOrderIndex] != other.orders[otherOrderIndex]) return false
            thisOrderIndex = (thisOrderIndex + 1) % this.maxOrderCount
            otherOrderIndex = (otherOrderIndex + 1) % other.maxOrderCount
        }

        // Check if both iterated to their respective tails
        if (thisOrderIndex != this.orderTail || otherOrderIndex != other.orderTail) return false

        return true
    }

    override fun hashCode(): Int {
        var result = levelIx
        result = 31 * result + side.hashCode()
        result = 31 * result + price.hashCode()
        result = 31 * result + maxOrderCount
        result = 31 * result + totalQuantity.hashCode()
        result = 31 * result + orderHead
        result = 31 * result + orderTail

        // include orders between head and tail
        var currentIndex = orderHead
        while (currentIndex != orderTail) {
            result = 31 * result + orders[currentIndex].hashCode()
            currentIndex = (currentIndex + 1) % maxOrderCount
        }

        return result
    }
}

data class Execution(
    val counterOrder: LevelOrder,
    val amount: BigInteger,
    val price: BigDecimal,
    val counterOrderExhausted: Boolean,
)

data class AddOrderResult(
    val disposition: OrderDisposition,
    val executions: List<Execution>,
)

data class RemoveOrderResult(
    val wallet: WalletAddress,
    val baseAssetAmount: BigInteger,
    val quoteAssetAmount: BigInteger,
)

data class ChangeOrderResult(
    val wallet: WalletAddress,
    val disposition: OrderDisposition,
    val executions: List<Execution>,
    val baseAssetDelta: BigInteger,
    val quoteAssetDelta: BigInteger,
)
