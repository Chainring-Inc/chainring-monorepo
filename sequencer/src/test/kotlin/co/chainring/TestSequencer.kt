package co.chainring

import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.toAsset
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toOrderGuid
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.BalanceChange
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderChangeRejected
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerError
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.newQuantityOrNull
import co.chainring.testutils.SequencerClient
import co.chainring.testutils.inSats
import co.chainring.testutils.inWei
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.pow
import kotlin.random.Random

class TestSequencer {

    @Test
    fun `Test basic order matching`() {
        val sequencer = SequencerClient()

        val marketId = MarketId("BTC1/ETH1")
        sequencer.createMarket(marketId)
        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()
        // maker deposits some of both assets -- 1 BTC, 1 ETH
        sequencer.deposit(maker, marketId.baseAsset(), BigDecimal.ONE.inSats())
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        // place an order and see that it gets accepted
        val response = sequencer.addOrder(marketId, BigDecimal("0.00012345").inSats(), "17.500", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = sequencer.addOrder(marketId, BigDecimal("0.00054321").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        val response3 = sequencer.addOrder(marketId, BigDecimal("0.00043210").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("0.00043210").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellGuid)
        assertEquals(takerOrder.guid, trade.buyGuid)

        // each of the maker and taker should have two balance changed messages, one for each asset
        assertEquals(4, response3.balancesChangedCount)
        val makerBalanceChanges = response3.balancesChangedList.filter { it.wallet == maker.value }
        val takerBalanceChanges = response3.balancesChangedList.filter { it.wallet == taker.value }
        assertEquals(2, makerBalanceChanges.size)
        assertEquals(2, takerBalanceChanges.size)
        val makerBaseBalanceChange = makerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val makerQuoteBalanceChange = makerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(-BigDecimal("0.00043210").inSats(), makerBaseBalanceChange.delta.toBigInteger())
        assertEquals(BigDecimal("0.007583355").inWei(), makerQuoteBalanceChange.delta.toBigInteger())

        val takerBaseBalanceChange = takerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val takerQuoteBalanceChange = takerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(BigDecimal("0.00043210").inSats(), takerBaseBalanceChange.delta.toBigInteger())
        assertEquals(-BigDecimal("0.007583355").inWei(), takerQuoteBalanceChange.delta.toBigInteger())
        // balances now should be:
        //   maker BTC1 = 1 - 0.00043210 = 0.99956790
        //         ETH1 = 1 + .007583355 = 1.007583355
        //   taker BTC1 = 0.00043210
        //         ETH1 = 1 - .007583355 = 0.992416645

        // now try a market sell which can only be partially filled and see that it gets executed
        val response4 = sequencer.addOrder(marketId, BigDecimal("0.00012346").inSats(), null, taker, Order.Type.MarketSell)
        assertEquals(2, response4.ordersChangedCount)
        val takerOrder2 = response4.ordersChangedList[0]
        assertEquals(OrderDisposition.PartiallyFilled, takerOrder2.disposition)
        val makerOrder2 = response4.ordersChangedList[1]
        assertEquals(OrderDisposition.Filled, makerOrder2.disposition)
        val trade2 = response4.tradesCreatedList.first()
        assertEquals("17.500".toBigDecimal().toDecimalValue(), trade2.price)
        assertEquals(BigDecimal("0.00012345").inSats().toIntegerValue(), trade2.amount)
        assertEquals(makerOrder2.guid, trade2.buyGuid)
        assertEquals(takerOrder2.guid, trade2.sellGuid)
        // verify the remaining balances for maker and taker (withdraw a large amount - returned balance change will
        // indicate what the balance was)
        // expected balances:
        //
        //   maker BTC1 = 0.00956790 + .00012345 = 0.99969135
        //         ETH1 = 1.007583355 - 0.002160375 = 1.00542298
        //   taker BTC1 = 0.00043210 - 0.00012345 = 0.00030865
        //         ETH1 = 0.992416645 + 0.002160375 = 0.99457702
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.99969135").inSats())
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("1.00542298").inWei())
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.00030865").inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.99457702").inWei())
    }

    private fun SequencerResponse.orderGuid() = this.ordersChangedList.first().guid

    @Test
    fun `Test a market order that executes against multiple orders at multiple levels`() {
        val sequencer = SequencerClient()

        val marketId = MarketId("BTC2/ETH2")
        sequencer.createMarket(marketId)
        val lp1 = 123457689L.toWalletAddress()
        val lp2 = 987654321L.toWalletAddress()
        val tkr = 555555555L.toWalletAddress()
        sequencer.deposit(lp1, marketId.baseAsset(), BigInteger.valueOf(31000))
        sequencer.deposit(lp2, marketId.baseAsset(), BigInteger.valueOf(31000))
        val sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.550", lp1, Order.Type.LimitSell)
        val sell2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.550", lp2, Order.Type.LimitSell)
        val sell3 = sequencer.addOrder(marketId, BigInteger.valueOf(10000), "17.600", lp1, Order.Type.LimitSell)
        val sell4 = sequencer.addOrder(marketId, BigInteger.valueOf(10000), "17.600", lp2, Order.Type.LimitSell)
        val sell5 = sequencer.addOrder(marketId, BigInteger.valueOf(20000), "17.700", lp1, Order.Type.LimitSell)
        val sell6 = sequencer.addOrder(marketId, BigInteger.valueOf(20000), "17.700", lp2, Order.Type.LimitSell)
        // clearing price would be (2000 * 17.55 + 15000 * 17.6) / 17000 = 17.594
        // notional is 17000 * 17.594 * 10^10
        sequencer.deposit(tkr, marketId.quoteAsset(), BigInteger("2990980000000000"))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(17000), null, tkr, Order.Type.MarketBuy)
        assertEquals(5, response.ordersChangedCount)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response.ordersChangedList[3].disposition)
        response.ordersChangedList[4].apply {
            assertEquals(OrderDisposition.PartiallyFilled, disposition)
            assertEquals(BigInteger.valueOf(5000), this.newQuantityOrNull?.toBigInteger())
        }
        assertEquals(
            listOf(sell1.orderGuid(), sell2.orderGuid(), sell3.orderGuid(), sell4.orderGuid()),
            response.tradesCreatedList.map { it.sellGuid },
        )
        assertEquals(
            listOf(1000, 1000, 10000, 5000),
            response.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.550", "17.550", "17.600", "17.600"),
            response.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
        // place another market order to exhaust remaining limit orders
        // clearing price would be (5000 * 17.6 + 40000 * 17.7) / 45000 = 17.689
        // notional is 45000 * 17.689 * 10^10
        sequencer.deposit(tkr, marketId.quoteAsset(), BigInteger("7960050000000000"))
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(45000), null, tkr, Order.Type.MarketBuy)
        assertEquals(4, response2.ordersChangedCount)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[3].disposition)
        assertEquals(
            listOf(sell4.orderGuid(), sell5.orderGuid(), sell6.orderGuid()),
            response2.tradesCreatedList.map { it.sellGuid },
        )
        assertEquals(
            listOf(5000, 20000, 20000),
            response2.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.600", "17.700", "17.700"),
            response2.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
    }

    @Test
    fun `test balances`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val walletAddress = rnd.nextLong().toWalletAddress()
        val asset = "ETH".toAsset()
        val amount = BigInteger.valueOf(1000)
        // do a deposit
        sequencer.deposit(walletAddress, asset, amount)
        // withdraw half
        sequencer.withdrawal(walletAddress, asset, amount / BigInteger.TWO)
        // request to withdraw amount, only half should be withdrawn
        sequencer.withdrawal(walletAddress, asset, amount, amount / BigInteger.TWO)
        // attempt to withdraw more does not return a balance change
        sequencer.withdrawal(walletAddress, asset, BigInteger.ONE, null)
        // attempt to withdraw from an unknown wallet or asset does not return a balance change
        sequencer.withdrawal(rnd.nextLong().toWalletAddress(), asset, BigInteger.ONE, null)
        sequencer.withdrawal(walletAddress, "PEPE".toAsset(), BigInteger.ONE, null)
        // can combine deposits and withdrawals in a batch - amount should be net
        sequencer.depositsAndWithdrawals(walletAddress, asset, listOf(BigInteger.TEN, BigInteger.ONE.negate()))
        // if it nets to 0, no balance change returned
        sequencer.depositsAndWithdrawals(walletAddress, asset, listOf(BigInteger.TEN.negate(), BigInteger.TEN), null)
    }

    @Test
    fun `test limit checking on orders`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId1 = MarketId("BTC3/ETH3")
        sequencer.createMarket(marketId1)
        // cannot place a buy or sell limit order without any deposits
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy).error)

        // deposit some base and can sell
        sequencer.deposit(maker, marketId1.baseAsset(), BigInteger.valueOf(1000))
        val response = sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // deposit some quote and can buy
        sequencer.deposit(maker, marketId1.quoteAsset(), BigDecimal.valueOf(17.50 * 1000 * 10.0.pow(10)).toBigInteger())
        val response2 = sequencer.addOrder(marketId1, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // but now that we've exhausted our balance we can't add more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.ONE, "17.55", maker, Order.Type.LimitSell).error)
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.ONE, "0.05", maker, Order.Type.LimitBuy).error)

        // but we can reuse the same liquidity in another market
        val marketId2 = MarketId("ETH3/USDC3")
        sequencer.createMarket(marketId2, baseDecimals = 18, quoteDecimals = 6)
        val response3 = sequencer.addOrder(marketId2, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // if we deposit some more we can add another order
        sequencer.deposit(maker, marketId1.baseAsset(), BigInteger.TEN)
        val response4 = sequencer.addOrder(marketId1, BigInteger.TEN, "17.60", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response4.ordersChangedList.first().disposition)

        // but not more
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId1, BigInteger.ONE, "17.55", maker, Order.Type.LimitSell).error)

        // unless a trade increases the balance
        val taker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(taker, marketId1.baseAsset(), BigInteger.TEN)
        val response5 = sequencer.addOrder(marketId1, BigInteger.TEN, null, taker, Order.Type.MarketSell)
        assertEquals(OrderDisposition.Filled, response5.ordersChangedList.first().disposition)

        val response6 = sequencer.addOrder(marketId1, BigInteger.ONE, "17.60", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response6.ordersChangedList.first().disposition)
    }

    @Test
    fun `test LimitBuy order can cross the market`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId = MarketId("BTC8/ETH8")
        sequencer.createMarket(marketId)

        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(2000))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.quoteAsset(), BigDecimal.valueOf(18.00 * 3000 * 10.0.pow(10)).toBigInteger())

        // limit order can cross the market and be filled immediately
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "18.00", crossingTheMarketMaker, Order.Type.LimitBuy)
        val r2TakerOrder = response2.ordersChangedList.first()
        val r2MakerOrder = response2.ordersChangedList.last()
        val r2trade = response2.tradesCreatedList.first()
        assertEquals(OrderDisposition.Filled, r2TakerOrder.disposition)
        assertEquals(OrderDisposition.PartiallyFilled, r2MakerOrder.disposition)
        assertEquals("17.550".toBigDecimal().toDecimalValue(), r2trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r2trade.amount)
        assertEquals(r2MakerOrder.guid, r2trade.sellGuid)
        assertEquals(r2TakerOrder.guid, r2trade.buyGuid)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedTakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.0001755").inWei(),
        )

        // or filled partially with remaining limit amount stays on the book
        val response3 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "18.00", crossingTheMarketMaker, Order.Type.LimitBuy)
        val r3TakerOrder = response3.ordersChangedList.first()
        val r3MakerOrder = response3.ordersChangedList.last()
        assertEquals(OrderDisposition.PartiallyFilled, r3TakerOrder.disposition)
        assertEquals(OrderDisposition.Filled, r3MakerOrder.disposition)
        val r3trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), r3trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r3trade.amount)
        assertEquals(r3MakerOrder.guid, r3trade.sellGuid)
        assertEquals(r3TakerOrder.guid, r3trade.buyGuid)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedTakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.0001755").inWei(),
        )
    }

    @Test
    fun `test LimitBuy order can cross the market filling LimitSell orders at multiple levels until limit price`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId = MarketId("BTC9/ETH9")
        sequencer.createMarket(marketId)

        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(2000))
        val sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "17.55", maker, Order.Type.LimitSell)
        val sell2 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "18.00", maker, Order.Type.LimitSell)
        val sell3 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "18.50", maker, Order.Type.LimitSell)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "19.00", maker, Order.Type.LimitSell)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "19.50", maker, Order.Type.LimitSell)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.quoteAsset(), BigDecimal.valueOf(18.2101 * 500 * 10.0.pow(10)).toBigInteger())
        assertEquals(SequencerError.ExceedsLimit, sequencer.addOrder(marketId, BigInteger.valueOf(500), "18.50", crossingTheMarketMaker, Order.Type.LimitBuy).error)
        // limit check passes on lower deposited amount due to partial filling by market price
        sequencer.deposit(crossingTheMarketMaker, marketId.quoteAsset(), BigDecimal.valueOf(0.0001 * 500 * 10.0.pow(10)).toBigInteger())

        // limit order is partially filled until limit price is reached
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(500), "18.50", crossingTheMarketMaker, Order.Type.LimitBuy)
        assertEquals(4, response2.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, response2.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[3].disposition)
        assertEquals(
            listOf(sell1.orderGuid(), sell2.orderGuid(), sell3.orderGuid()),
            response2.tradesCreatedList.map { it.sellGuid },
        )
        assertEquals(
            listOf(response2.orderGuid()),
            response2.tradesCreatedList.map { it.buyGuid }.distinct(),
        )
        assertEquals(
            listOf(100, 100, 100),
            response2.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.550", "18.000", "18.500"),
            response2.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedTakerBaseBalanceChange = BigDecimal("0.000003").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.00005405").inWei(),
        )
    }

    @Test
    fun `test LimitSell order can cross the market`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId = MarketId("BTC10/ETH10")
        sequencer.createMarket(marketId)

        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.valueOf(17.50 * 2000 * 10.0.pow(10)).toBigInteger())
        val response1 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.50", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response1.ordersChangedList.first().disposition)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.baseAsset(), BigInteger.valueOf(3000))

        // limit order can cross the market and be filled immediately
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.00", crossingTheMarketMaker, Order.Type.LimitSell)
        val r2TakerOrder = response2.ordersChangedList.first()
        val r2MakerOrder = response2.ordersChangedList.last()
        val r2trade = response2.tradesCreatedList.first()
        assertEquals(OrderDisposition.Filled, r2TakerOrder.disposition)
        assertEquals(OrderDisposition.PartiallyFilled, r2MakerOrder.disposition)
        assertEquals("17.500".toBigDecimal().toDecimalValue(), r2trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r2trade.amount)
        assertEquals(r2MakerOrder.guid, r2trade.buyGuid)
        assertEquals(r2TakerOrder.guid, r2trade.sellGuid)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedTakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.000175").inWei(),
        )

        // or filled partially with remaining limit amount stays on the book
        val response3 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.00", crossingTheMarketMaker, Order.Type.LimitSell)
        val r3TakerOrder = response3.ordersChangedList.first()
        val r3MakerOrder = response3.ordersChangedList.last()
        assertEquals(OrderDisposition.PartiallyFilled, r3TakerOrder.disposition)
        assertEquals(OrderDisposition.Filled, r3MakerOrder.disposition)
        val r3trade = response3.tradesCreatedList.first()
        assertEquals("17.500".toBigDecimal().toDecimalValue(), r3trade.price)
        assertEquals(BigDecimal("0.00001").inSats().toIntegerValue(), r3trade.amount)
        assertEquals(r3MakerOrder.guid, r3trade.buyGuid)
        assertEquals(r3TakerOrder.guid, r3trade.sellGuid)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedTakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.000175").inWei(),
        )
    }

    @Test
    fun `test LimitSell order can cross the market filling LimitBuy orders at multiple levels until limit price`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId = MarketId("BTC11/ETH11")
        sequencer.createMarket(marketId)

        // deposit and prepare liquidity
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.valueOf(17.50 * 2000 * 10.0.pow(10)).toBigInteger())
        val buy1 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "17.50", maker, Order.Type.LimitBuy)
        val buy2 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "17.00", maker, Order.Type.LimitBuy)
        val buy3 = sequencer.addOrder(marketId, BigInteger.valueOf(100), "16.50", maker, Order.Type.LimitBuy)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "16.00", maker, Order.Type.LimitBuy)
        sequencer.addOrder(marketId, BigInteger.valueOf(100), "15.50", maker, Order.Type.LimitBuy)

        // prepare second maker
        val crossingTheMarketMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(crossingTheMarketMaker, marketId.baseAsset(), BigInteger.valueOf(3000))

        // limit order is partially filled until price is reached
        val response2 = sequencer.addOrder(marketId, BigInteger.valueOf(500), "16.50", crossingTheMarketMaker, Order.Type.LimitSell)
        assertEquals(4, response2.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, response2.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[1].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[2].disposition)
        assertEquals(OrderDisposition.Filled, response2.ordersChangedList[3].disposition)
        assertEquals(
            listOf(buy1.orderGuid(), buy2.orderGuid(), buy3.orderGuid()),
            response2.tradesCreatedList.map { it.buyGuid },
        )
        assertEquals(
            listOf(response2.orderGuid()),
            response2.tradesCreatedList.map { it.sellGuid }.distinct(),
        )
        assertEquals(
            listOf(100, 100, 100),
            response2.tradesCreatedList.map { it.amount.toBigInteger().toInt() },
        )
        assertEquals(
            listOf("17.500", "17.000", "16.500"),
            response2.tradesCreatedList.map { it.price.toBigDecimal().toString() },
        )
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = crossingTheMarketMaker,
            expectedTakerBaseBalanceChange = -BigDecimal("0.000003").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.000051").inWei(),
        )
    }

    @Test
    fun `test order cancel`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId = MarketId("BTC4/ETH4")
        sequencer.createMarket(marketId)
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(1000))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
        val response2 = sequencer.cancelOrder(marketId, response.orderGuid(), maker)
        assertEquals(OrderDisposition.Canceled, response2.ordersChangedList.first().disposition)
        // try canceling an order which has been partially filled
        val response3 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        val taker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        val response4 = sequencer.addOrder(marketId, BigInteger.valueOf(500), null, taker, Order.Type.MarketBuy)
        assertEquals(OrderDisposition.Filled, response4.ordersChangedList.first().disposition)

        // have taker try to cancel maker order
        val response5 = sequencer.cancelOrder(marketId, response3.orderGuid(), taker)
        assertEquals(0, response5.ordersChangedList.size)
        assertEquals(1, response5.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.NotForWallet, response5.ordersChangeRejectedList.first().reason)

        val response6 = sequencer.cancelOrder(marketId, response3.orderGuid(), maker)
        assertEquals(OrderDisposition.Canceled, response6.ordersChangedList.first().disposition)

        // cancel an invalid order
        val response7 = sequencer.cancelOrder(marketId, response3.orderGuid() + 2, maker)
        assertEquals(0, response7.ordersChangedList.size)
        assertEquals(1, response7.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.DoesNotExist, response7.ordersChangeRejectedList.first().reason)
    }

    @Test
    fun `test order change`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId = MarketId("BTC5/ETH5")
        sequencer.createMarket(marketId)
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(1000))
        val response = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // reduce amount
        val response2 = sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 999L, "17.55", maker)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // cannot increase amount beyond 1000L since there is not enough collateral
        assertEquals(SequencerError.ExceedsLimit, sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 1001L, "17.55", maker).error)

        // but can change price
        val response3 = sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 999L, "17.60", maker)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // can also add a new order for 1
        val response4 = sequencer.addOrder(marketId, BigInteger.ONE, "17.55", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response4.ordersChangedList.first().disposition)

        // can change the price to cross the market, disposition is 'Accepted' since there is no liquidity yet available in the market
        val response5 = sequencer.changeOrder(marketId, response.orderGuid().toOrderGuid(), 999L, "15.50", maker)
        assertEquals(OrderDisposition.Accepted, response5.ordersChangedList.first().disposition)

        // check for a limit buy
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei())
        val response6 = sequencer.addOrder(marketId, BigDecimal.ONE.inSats(), "10.00", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response6.ordersChangedList.first().disposition)

        // cannot increase amount since we have consumed all the collateral
        assertEquals(
            SequencerError.ExceedsLimit,
            sequencer.changeOrder(
                marketId,
                response6.orderGuid().toOrderGuid(),
                BigDecimal.ONE.inSats().toLong() + 1,
                "10.00",
                maker,
            ).error,
        )

        // also cannot increase price
        assertEquals(
            SequencerError.ExceedsLimit,
            sequencer.changeOrder(
                marketId,
                response6.orderGuid().toOrderGuid(),
                BigDecimal.ONE.inSats().toLong(),
                "10.05",
                maker,
            ).error,
        )

        // but can decrease amount or decrease price
        val response7 = sequencer.changeOrder(marketId, response6.orderGuid().toOrderGuid(), BigDecimal.ONE.inSats().toLong() - 1, "10.00", maker)
        assertEquals(OrderDisposition.Accepted, response7.ordersChangedList.first().disposition)

        val response8 = sequencer.changeOrder(marketId, response6.orderGuid().toOrderGuid(), BigDecimal.ONE.inSats().toLong(), "9.95", maker)
        assertEquals(OrderDisposition.Accepted, response8.ordersChangedList.first().disposition)

        // different wallet try to update an order
        val taker = rnd.nextLong().toWalletAddress()
        val response9 = sequencer.changeOrder(marketId, response6.orderGuid().toOrderGuid(), BigDecimal.ONE.inSats().toLong() - 1, "9.95", taker)
        assertEquals(0, response9.ordersChangedList.size)
        assertEquals(1, response9.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.NotForWallet, response9.ordersChangeRejectedList.first().reason)

        // update an invalid order.
        val response10 = sequencer.changeOrder(marketId, (response6.orderGuid() + 1).toOrderGuid(), BigDecimal.ONE.inSats().toLong() - 1, "9.95", maker)
        assertEquals(0, response10.ordersChangedList.size)
        assertEquals(1, response10.ordersChangeRejectedList.size)
        assertEquals(OrderChangeRejected.Reason.DoesNotExist, response10.ordersChangeRejectedList.first().reason)
    }

    @Test
    fun `test order change when new price crosses the market`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val marketId = MarketId("BTC12/ETH12")
        sequencer.createMarket(marketId)

        // onboard maker and prepare market
        val maker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(maker, marketId.baseAsset(), BigInteger.valueOf(2000))
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.valueOf(17.50 * 2000 * 10.0.pow(10)).toBigInteger())
        val m1sell2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "18.00", maker, Order.Type.LimitSell)
        val m1sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.55", maker, Order.Type.LimitSell)
        val m1buy1 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.50", maker, Order.Type.LimitBuy)
        val m1buy2 = sequencer.addOrder(marketId, BigInteger.valueOf(1000), "17.25", maker, Order.Type.LimitBuy)

        // onboard another maker
        val anotherMaker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(anotherMaker, marketId.baseAsset(), BigInteger.valueOf(2000))
        sequencer.deposit(anotherMaker, marketId.quoteAsset(), BigDecimal.valueOf(17.7 * 2000 * 10.0.pow(10)).toBigInteger())
        val m2sell1 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "18.00", anotherMaker, Order.Type.LimitSell)
        val m2buy1 = sequencer.addOrder(marketId, BigInteger.valueOf(2000), "17.00", anotherMaker, Order.Type.LimitBuy)

        // verify setup is successful
        listOf(m1buy1, m1buy2, m1sell1, m1sell2, m2buy1, m2sell1).forEach { response ->
            assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)
        }

        // update limit sell order to cross the market. Results in immediate partial execution.
        val m2sell1ChangeResponse = sequencer.changeOrder(marketId, m2sell1.orderGuid().toOrderGuid(), 2000, "17.30", anotherMaker)
        assertEquals(OrderDisposition.PartiallyFilled, m2sell1ChangeResponse.ordersChangedList.first().disposition)
        assertEquals(2, m2sell1ChangeResponse.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, m2sell1ChangeResponse.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, m2sell1ChangeResponse.ordersChangedList[1].disposition)
        assertEquals(1, m2sell1ChangeResponse.tradesCreatedCount)
        assertEquals(m1buy1.orderGuid(), m2sell1ChangeResponse.tradesCreatedList.first().buyGuid)
        assertEquals(m2sell1ChangeResponse.orderGuid(), m2sell1ChangeResponse.tradesCreatedList.first().sellGuid)
        assertEquals(1000, m2sell1ChangeResponse.tradesCreatedList.first().amount.toBigInteger().toInt())
        assertEquals("17.500", m2sell1ChangeResponse.tradesCreatedList.first().price.toBigDecimal().toString())
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = m2sell1ChangeResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = anotherMaker,
            expectedTakerBaseBalanceChange = -BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.0001750").inWei(),
        )

        // now cancel own order (limit sell 17.30) to avoid matching
        sequencer.cancelOrder(marketId, m2sell1ChangeResponse.orderGuid(), anotherMaker)

        // update limit buy order to cross the market (immediate partial execution)
        val m2buy1ChangeResponse = sequencer.changeOrder(marketId, m2buy1.orderGuid().toOrderGuid(), 2000, "17.75", anotherMaker)
        assertEquals(2, m2buy1ChangeResponse.ordersChangedCount)
        assertEquals(OrderDisposition.PartiallyFilled, m2buy1ChangeResponse.ordersChangedList[0].disposition)
        assertEquals(OrderDisposition.Filled, m2buy1ChangeResponse.ordersChangedList[1].disposition)
        assertEquals(1, m2buy1ChangeResponse.tradesCreatedCount)
        assertEquals(m1sell1.orderGuid(), m2buy1ChangeResponse.tradesCreatedList.first().sellGuid)
        assertEquals(m2buy1ChangeResponse.orderGuid(), m2buy1ChangeResponse.tradesCreatedList.first().buyGuid)
        assertEquals(1000, m2buy1ChangeResponse.tradesCreatedList.first().amount.toBigInteger().toInt())
        assertEquals("17.550", m2buy1ChangeResponse.tradesCreatedList.first().price.toBigDecimal().toString())
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = m2buy1ChangeResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = anotherMaker,
            expectedTakerBaseBalanceChange = BigDecimal("0.00001").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.0001755").inWei(),
        )
    }

    @Test
    fun `test auto-reduce from trades`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        // create two markets
        val marketId1 = MarketId("BTC6/ETH6")
        val marketId2 = MarketId("ETH6/USDC6")
        val marketId3 = MarketId("XXX6/ETH6")
        sequencer.createMarket(marketId1, tickSize = BigDecimal.ONE, marketPrice = BigDecimal.valueOf(10.5), baseDecimals = 8, quoteDecimals = 18)
        sequencer.createMarket(marketId2, tickSize = BigDecimal.ONE, marketPrice = BigDecimal.valueOf(9.5), baseDecimals = 18, quoteDecimals = 6)
        sequencer.createMarket(marketId3, tickSize = BigDecimal.ONE, marketPrice = BigDecimal.valueOf(20.5), baseDecimals = 1, quoteDecimals = 18)
        // maker deposits 10 ETH
        sequencer.deposit(maker, marketId1.quoteAsset(), BigDecimal.TEN.inWei())
        // maker adds a bid in market1 using all 10 eth
        val response1 = sequencer.addOrder(marketId1, BigDecimal.ONE.inSats(), "10", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response1.ordersChangedList.first().disposition)
        // maker adds an offer in market2 using all 10 eth
        val response2 = sequencer.addOrder(marketId2, BigDecimal.TEN.inWei(), "10", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)
        // maker also adds a bid in market3 using all 10 eth
        val response3 = sequencer.addOrder(marketId3, BigInteger.valueOf(5), "20", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // now add a taker who will hit the market1 bid selling 0.6 BTC
        val taker = rnd.nextLong().toWalletAddress()
        sequencer.deposit(taker, marketId1.baseAsset(), BigDecimal.valueOf(0.6).inSats())
        val response4 = sequencer.addOrder(marketId1, BigDecimal.valueOf(0.6).inSats(), null, taker, Order.Type.MarketSell)
        assertEquals(OrderDisposition.Filled, response4.ordersChangedList.first().disposition)

        // the maker's offer in market2 should be auto-reduced
        val reducedOffer = response4.ordersChangedList.find { it.guid == response2.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedOffer.disposition)
        assertEquals(BigDecimal.valueOf(4).inWei(), reducedOffer.newQuantity.toBigInteger())

        // also the maker's bid in market3 should be auto-reduced
        val reducedBid = response4.ordersChangedList.find { it.guid == response3.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedBid.disposition)
        assertEquals(BigInteger.valueOf(2), reducedBid.newQuantity.toBigInteger())
    }

    @Test
    fun `test auto-reduce from withdrawals`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val maker = rnd.nextLong().toWalletAddress()
        val marketId = MarketId("BTC7/ETH7")
        sequencer.createMarket(marketId)
        // maker deposits 10 BTC
        sequencer.deposit(maker, marketId.baseAsset(), BigDecimal.TEN.inSats())
        // maker adds two offers combined which use all 10 BTC
        val response1 = sequencer.addOrder(marketId, BigDecimal(4).inSats(), "17.75", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response1.ordersChangedList.first().disposition)
        val response2 = sequencer.addOrder(marketId, BigDecimal(6).inSats(), "18.00", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // now maker withdraws 7 BTC
        val response3 = sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal(7).inSats())
        val reducedOffer1 = response3.ordersChangedList.find { it.guid == response1.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedOffer1.disposition)
        assertEquals(BigDecimal(3).inSats(), reducedOffer1.newQuantity.toBigInteger())
        val reducedOffer2 = response3.ordersChangedList.find { it.guid == response2.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedOffer2.disposition)
        assertEquals(BigInteger.ZERO, reducedOffer2.newQuantity.toBigInteger())
    }

    @Test
    fun `test failed withdrawals`() {
        val sequencer = SequencerClient()
        val rnd = Random(0)
        val walletAddress = rnd.nextLong().toWalletAddress()
        val asset = "ETH".toAsset()
        val amount = BigInteger.valueOf(1000)
        // do a deposit
        sequencer.deposit(walletAddress, asset, amount)
        // withdraw half
        sequencer.withdrawal(walletAddress, asset, amount / BigInteger.TWO)

        // withdraw other half
        sequencer.withdrawal(walletAddress, asset, amount / BigInteger.TWO)

        // fail the 2 withdrawals
        sequencer.failedWithdrawals(walletAddress, asset, listOf(amount / BigInteger.TWO, amount / BigInteger.TWO))

        // should still be able to withdraw full amount since we rolled back the 2 halves
        sequencer.withdrawal(walletAddress, asset, amount)
    }

    @Test
    fun `Test failed settlements`() {
        val sequencer = SequencerClient()

        val marketId = MarketId("BTC8/ETH8")
        sequencer.createMarket(marketId)
        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()
        // maker deposits some of both assets -- 2 BTC, 2 ETH
        val makerBaseBalance = BigDecimal("2").inSats()
        val makerQuoteBalance = BigDecimal("2").inWei()
        sequencer.deposit(maker, marketId.baseAsset(), makerBaseBalance)
        sequencer.deposit(maker, marketId.quoteAsset(), makerQuoteBalance)
        // place an order and see that it gets accepted
        val response = sequencer.addOrder(marketId, BigDecimal("0.00012345").inSats(), "17.500", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = sequencer.addOrder(marketId, BigDecimal("0.00054321").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        val response3 = sequencer.addOrder(marketId, BigDecimal("0.00043210").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("0.00043210").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellGuid)
        assertEquals(takerOrder.guid, trade.buyGuid)

        // each of the maker and taker should have two balance changed messages, one for each asset
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response3.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedTakerBaseBalanceChange = BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.007583355").inWei(),
        )

        // now rollback the settlement - all the balances should be back to their original values
        val failedSettlementResponse = sequencer.failedSettlement(taker, maker, marketId, trade)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = failedSettlementResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedTakerBaseBalanceChange = -BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.007583355").inWei(),
        )
        // all balances should be back to original values
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), makerBaseBalance)
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), makerQuoteBalance)
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal.ONE.inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal.ONE.inWei())
    }

    @Test
    fun `Test autoreduce on failed settlements`() {
        val sequencer = SequencerClient()

        val marketId = MarketId("BTC9/ETH9")
        sequencer.createMarket(marketId)
        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()
        // maker deposits some of both assets -- 2 BTC, 2 ETH
        val makerBaseBalance = BigDecimal("2").inSats()
        val makerQuoteBalance = BigDecimal("10").inWei()
        sequencer.deposit(maker, marketId.baseAsset(), makerBaseBalance)
        sequencer.deposit(maker, marketId.quoteAsset(), makerQuoteBalance)

        // place a sell order
        val response = sequencer.addOrder(marketId, BigDecimal("2").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal("40").inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        val response2 = sequencer.addOrder(marketId, BigDecimal("1").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response2.ordersChangedCount)
        val takerOrder = response2.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response2.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response.orderGuid(), makerOrder.guid)
        val trade = response2.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("1").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellGuid)
        assertEquals(takerOrder.guid, trade.buyGuid)

        // each of the maker and taker should have two balance changed messages, one for each asset
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response2.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedTakerBaseBalanceChange = BigDecimal("1").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("17.55").inWei(),
        )

        // place an order for taker to sell 2 BTC - they started with 1 and just bought 1, so they have 2 total
        val response3 = sequencer.addOrder(marketId, BigDecimal("2").inSats(), "17.500", taker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response3.ordersChangedList.first().disposition)

        // now rollback the settlement - all the balances should be back to their original values
        val failedSettlementResponse = sequencer.failedSettlement(taker, maker, marketId, trade)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = failedSettlementResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedTakerBaseBalanceChange = -BigDecimal("1").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("17.55").inWei(),
        )
        assertEquals(failedSettlementResponse.ordersChangedList.size, 1)
        val reducedBid = failedSettlementResponse.ordersChangedList.find { it.guid == response3.orderGuid() }!!
        assertEquals(OrderDisposition.AutoReduced, reducedBid.disposition)
        assertEquals(BigDecimal("1").inSats(), reducedBid.newQuantity.toBigInteger())

        // all balances should be back to original values
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), makerBaseBalance)
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal("100").inWei(), makerQuoteBalance)
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal.ONE.inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal("100").inWei(), BigDecimal("40").inWei())
    }

    @Test
    fun `Test failed settlements - balances can go negative`() {
        val sequencer = SequencerClient()

        val marketId = MarketId("BTC10/ETH10")
        sequencer.createMarket(marketId)
        val maker = 123456789L.toWalletAddress()
        val taker = 555111555L.toWalletAddress()
        // maker deposits some of both assets -- 2 BTC, 2 ETH
        val makerBaseBalance = BigDecimal("2").inSats()
        val makerQuoteBalance = BigDecimal("2").inWei()
        sequencer.deposit(maker, marketId.baseAsset(), makerBaseBalance)
        sequencer.deposit(maker, marketId.quoteAsset(), makerQuoteBalance)

        // place an order and see that it gets accepted
        val response = sequencer.addOrder(marketId, BigDecimal("0.00012345").inSats(), "17.500", maker, Order.Type.LimitBuy)
        assertEquals(OrderDisposition.Accepted, response.ordersChangedList.first().disposition)

        // place a sell order
        val response2 = sequencer.addOrder(marketId, BigDecimal("0.00054321").inSats(), "17.550", maker, Order.Type.LimitSell)
        assertEquals(OrderDisposition.Accepted, response2.ordersChangedList.first().disposition)

        // place a market buy and see that it gets executed
        sequencer.deposit(taker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        val response3 = sequencer.addOrder(marketId, BigDecimal("0.00043210").inSats(), null, taker, Order.Type.MarketBuy)
        assertEquals(2, response3.ordersChangedCount)
        val takerOrder = response3.ordersChangedList[0]
        assertEquals(OrderDisposition.Filled, takerOrder.disposition)
        val makerOrder = response3.ordersChangedList[1]
        assertEquals(OrderDisposition.PartiallyFilled, makerOrder.disposition)
        assertEquals(response2.orderGuid(), makerOrder.guid)
        val trade = response3.tradesCreatedList.first()
        assertEquals("17.550".toBigDecimal().toDecimalValue(), trade.price)
        assertEquals(BigDecimal("0.00043210").inSats().toIntegerValue(), trade.amount)
        assertEquals(makerOrder.guid, trade.sellGuid)
        assertEquals(takerOrder.guid, trade.buyGuid)

        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = response3.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedTakerBaseBalanceChange = BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = -BigDecimal("0.007583355").inWei(),
        )

        // balances now should be:
        //   maker BTC1 = 2 - 0.00043210 = 1.99956790
        //         ETH1 = 2 + .007583355 = 2.007583355
        //   taker BTC1 = 0.00043210
        //         ETH1 = 1 - .007583355 = 0.992416645
        // withdraw everything
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("1.999567905").inSats())
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("2.007583355").inWei())
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("1.00043210").inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.992416645").inWei())

        // now rollback the settlement - some balances go negative (takers base balance, and makers quote balance
        val failedSettlementResponse = sequencer.failedSettlement(taker, maker, marketId, trade)
        verifyBalanceChanges(
            marketId = marketId,
            balancesChangedList = failedSettlementResponse.balancesChangedList,
            makerWallet = maker,
            takerWallet = taker,
            expectedTakerBaseBalanceChange = -BigDecimal("0.00043210").inSats(),
            expectedTakerQuoteBalanceChange = BigDecimal("0.007583355").inWei(),
        )
        sequencer.withdrawal(maker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.00043210").inSats())
        sequencer.withdrawal(taker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.007583355").inWei())
        // no balance change for these two since they went negative
        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), null)
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), null)

        // now deposit to take balance positive and withdraw all to make sure adjustments are properly applied
        sequencer.deposit(maker, marketId.quoteAsset(), BigDecimal.ONE.inWei())
        sequencer.deposit(taker, marketId.baseAsset(), BigDecimal.ONE.inSats())

        sequencer.withdrawal(maker, marketId.quoteAsset(), BigDecimal.TEN.inWei(), BigDecimal("0.992416645").inWei())
        sequencer.withdrawal(taker, marketId.baseAsset(), BigDecimal.TEN.inSats(), BigDecimal("0.999567905").inSats())
    }

    private fun verifyBalanceChanges(
        marketId: MarketId,
        balancesChangedList: MutableList<BalanceChange>,
        makerWallet: WalletAddress,
        takerWallet: WalletAddress,
        expectedTakerBaseBalanceChange: BigInteger,
        expectedTakerQuoteBalanceChange: BigInteger,
    ) {
        assertEquals(4, balancesChangedList.size)

        val makerBalanceChanges = balancesChangedList.filter { it.wallet == makerWallet.value }
        val takerBalanceChanges = balancesChangedList.filter { it.wallet == takerWallet.value }
        assertEquals(2, makerBalanceChanges.size)
        assertEquals(2, takerBalanceChanges.size)

        val makerBaseBalanceChange = makerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val makerQuoteBalanceChange = makerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(-expectedTakerBaseBalanceChange, makerBaseBalanceChange.delta.toBigInteger())
        assertEquals(-expectedTakerQuoteBalanceChange, makerQuoteBalanceChange.delta.toBigInteger())

        val takerBaseBalanceChange = takerBalanceChanges.find { it.asset == marketId.baseAsset().value }!!
        val takerQuoteBalanceChange = takerBalanceChanges.find { it.asset == marketId.quoteAsset().value }!!
        assertEquals(expectedTakerBaseBalanceChange, takerBaseBalanceChange.delta.toBigInteger())
        assertEquals(expectedTakerQuoteBalanceChange, takerQuoteBalanceChange.delta.toBigInteger())
    }
}
