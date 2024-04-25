package co.chainring

import co.chainring.core.Maker
import co.chainring.core.Taker
import co.chainring.core.model.db.MarketId
import co.chainring.core.toFundamentalUnits
import java.math.BigDecimal
import kotlin.random.Random

fun main() {
    val maker = Maker(
        tightness = 10, skew = 0, levels = 10,
        native = BigDecimal.TEN.movePointRight(18).toBigInteger(),
        assets = mapOf(
            //"ETH" to 200.toFundamentalUnits(18),
            "USDC" to 10.toFundamentalUnits(6),
            "DAI" to 10.toFundamentalUnits(18)
        )
    )
    val usdcDai = MarketId("USDC/DAI")
    val btcEth = MarketId("BTC/ETH")
    maker.start(listOf(usdcDai))
    val takers = (1..5).map {
        Taker(
            rate = Random.nextLong(5000, 20000),
            sizeFactor = Random.nextDouble(5.0, 20.0),
            native = null,
            assets = mapOf(
                "USDC" to 5.toFundamentalUnits(6),
                "DAI" to 5.toFundamentalUnits(18)
            )
        )
    }
    takers.forEach {
        it.start(listOf(usdcDai))
    }
    Thread.sleep(60000)
    maker.stop()
    takers.forEach {
        it.stop()
    }
}