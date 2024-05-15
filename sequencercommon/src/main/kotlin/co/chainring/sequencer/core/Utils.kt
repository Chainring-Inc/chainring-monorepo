package co.chainring.sequencer.core

import co.chainring.sequencer.proto.DecimalValue
import co.chainring.sequencer.proto.IntegerValue
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

fun BigDecimal.toDecimalValue(): DecimalValue = DecimalValue.newBuilder()
    .setScale(this.scale())
    .setPrecision(this.precision())
    .setValue(ByteString.copyFrom(this.unscaledValue().toByteArray()))
    .build()

fun DecimalValue.toBigDecimal(): BigDecimal = BigDecimal(
    BigInteger(this.value.toByteArray()),
    this.scale,
    MathContext(this.precision),
)

fun BigInteger.toIntegerValue(): IntegerValue = IntegerValue.newBuilder()
    .setValue(this.toByteArray().toByteString())
    .build()

fun IntegerValue.toBigInteger(): BigInteger = BigInteger(
    this.value.toByteArray(),
)

fun sumBigIntegers(a: BigInteger, b: BigInteger): BigInteger = a + b

fun Iterable<BigInteger>.sum(): BigInteger = reduce(::sumBigIntegers)

fun notional(amount: BigInteger, price: BigDecimal, baseDecimals: Int, quoteDecimals: Int): BigInteger =
    (amount.toBigDecimal() * price).movePointRight(quoteDecimals - baseDecimals).toBigInteger()

fun notionalFee(notional: BigInteger, feeRate: FeeRate): BigInteger =
    notional * feeRate.value.toBigInteger() / FeeRate.MAX_VALUE.toBigInteger()

fun notionalPlusFee(amount: BigInteger, price: BigDecimal, baseDecimals: Int, quoteDecimals: Int, feeRate: FeeRate): BigInteger =
    (amount.toBigDecimal() * price).movePointRight(quoteDecimals - baseDecimals).toBigInteger().let { notional ->
        notional + notionalFee(notional, feeRate)
    }

fun notionalPlusFee(amount: IntegerValue, price: DecimalValue, baseDecimals: Int, quoteDecimals: Int, feeRate: FeeRate): BigInteger =
    notionalPlusFee(amount.toBigInteger(), price.toBigDecimal(), baseDecimals, quoteDecimals, feeRate)
