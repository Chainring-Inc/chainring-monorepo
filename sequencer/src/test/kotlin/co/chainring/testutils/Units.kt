package co.chainring.testutils

import java.math.BigDecimal
import java.math.BigInteger

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return this.movePointRight(decimals).toBigInteger()
}

fun BigDecimal.inWei() = toFundamentalUnits(18)
fun BigDecimal.inSats() = toFundamentalUnits(8)
