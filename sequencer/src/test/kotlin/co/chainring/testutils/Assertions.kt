package co.chainring.testutils

import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.toWalletAddress
import co.chainring.sequencer.proto.SequencerResponse
import org.junit.jupiter.api.Assertions.assertEquals
import java.math.BigDecimal

data class ExpectedTrade(
    val buyOrderGuid: Long,
    val sellOrderGuid: Long,
    val price: BigDecimal,
    val amount: BigDecimal,
    val buyerFee: BigDecimal,
    val sellerFee: BigDecimal,
    val marketId: String? = null,
)

fun SequencerResponse.assertTrades(
    market: SequencerClient.Market,
    expectedTrades: List<ExpectedTrade>,
) {
    assertEquals(
        expectedTrades.map {
            it.copy(
                amount = it.amount.setScale(market.baseDecimals),
                buyerFee = it.buyerFee.setScale(market.quoteDecimals),
                sellerFee = it.sellerFee.setScale(market.quoteDecimals),
                marketId = market.id.value,
            )
        },
        tradesCreatedList.map {
            ExpectedTrade(
                buyOrderGuid = it.buyOrderGuid,
                sellOrderGuid = it.sellOrderGuid,
                price = market.tickSize.multiply(it.levelIx.toBigDecimal()),
                amount = it.amount.fromFundamentalUnits(market.baseDecimals),
                buyerFee = it.buyerFee.fromFundamentalUnits(market.quoteDecimals),
                sellerFee = it.sellerFee.fromFundamentalUnits(market.quoteDecimals),
                marketId = it.marketId,
            )
        },
    )
}

fun SequencerResponse.assertTrade(
    market: SequencerClient.Market,
    expectedTrade: ExpectedTrade,
    index: Int,
) {
    assertEquals(
        expectedTrade.copy(
            amount = expectedTrade.amount.setScale(market.baseDecimals),
            buyerFee = expectedTrade.buyerFee.setScale(market.quoteDecimals),
            sellerFee = expectedTrade.sellerFee.setScale(market.quoteDecimals),
            marketId = market.id.value,
        ),
        ExpectedTrade(
            buyOrderGuid = tradesCreatedList[index].buyOrderGuid,
            sellOrderGuid = tradesCreatedList[index].sellOrderGuid,
            price = market.tickSize.multiply(tradesCreatedList[index].levelIx.toBigDecimal()),
            amount = tradesCreatedList[index].amount.fromFundamentalUnits(market.baseDecimals),
            buyerFee = tradesCreatedList[index].buyerFee.fromFundamentalUnits(market.quoteDecimals),
            sellerFee = tradesCreatedList[index].sellerFee.fromFundamentalUnits(market.quoteDecimals),
            marketId = tradesCreatedList[index].marketId,
        ),
    )
}

fun SequencerResponse.assertBalanceChanges(
    market: SequencerClient.Market,
    expectedChanges: List<Triple<WalletAddress, SequencerClient.Asset, BigDecimal>>,
) {
    val changes = balancesChangedList.map {
        val asset = market.getAsset(it.asset)
        Triple(
            it.wallet.toWalletAddress(),
            asset,
            it.delta.fromFundamentalUnits(asset.decimals),
        )
    }

    assertEquals(
        expectedChanges.map { it.copy(third = it.third.setScale(it.second.decimals)) },
        changes,
    )
}
