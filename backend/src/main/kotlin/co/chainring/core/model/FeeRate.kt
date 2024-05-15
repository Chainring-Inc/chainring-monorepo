package co.chainring.core.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
@JvmInline
value class FeeRate(val value: Long) {
    init {
        require(value in 1..MAX_VALUE) { "Invalid fee rate" }
    }

    companion object {
        const val MAX_VALUE = 1_000_000L

        fun fromPercents(percents: Double): FeeRate =
            FeeRate((BigDecimal(percents) * BigDecimal(MAX_VALUE) / BigDecimal(100)).toLong())
    }
}
