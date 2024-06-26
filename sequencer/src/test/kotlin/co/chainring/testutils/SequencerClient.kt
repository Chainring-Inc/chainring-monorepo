package co.chainring.testutils

import co.chainring.core.model.Symbol
import co.chainring.core.model.db.FeeRates
import co.chainring.sequencer.apps.SequencerApp
import co.chainring.sequencer.core.Asset
import co.chainring.sequencer.core.MarketId
import co.chainring.sequencer.core.OrderGuid
import co.chainring.sequencer.core.WalletAddress
import co.chainring.sequencer.core.toBigDecimal
import co.chainring.sequencer.core.toBigInteger
import co.chainring.sequencer.core.toDecimalValue
import co.chainring.sequencer.core.toIntegerValue
import co.chainring.sequencer.core.toMarketId
import co.chainring.sequencer.proto.Order
import co.chainring.sequencer.proto.OrderChanged
import co.chainring.sequencer.proto.OrderDisposition
import co.chainring.sequencer.proto.SequencerRequest
import co.chainring.sequencer.proto.SequencerResponse
import co.chainring.sequencer.proto.TradeCreated
import co.chainring.sequencer.proto.balanceBatch
import co.chainring.sequencer.proto.feeRates
import co.chainring.sequencer.proto.market
import co.chainring.sequencer.proto.order
import co.chainring.sequencer.proto.orderBatch
import co.chainring.sequencer.proto.sequencerRequest
import org.junit.jupiter.api.Assertions.assertEquals
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertNotNull

class SequencerClient {
    private val sequencer = SequencerApp(checkpointsPath = null)

    data class Asset(
        val name: String,
        val decimals: Int,
    )

    data class WithdrawalFee(
        val asset: Symbol,
        val fee: BigInteger,
    )

    data class Market(
        val id: MarketId,
        val baseDecimals: Int,
        val quoteDecimals: Int,
    ) {
        val baseAsset: Asset = Asset(id.baseAsset().value, baseDecimals)
        val quoteAsset: Asset = Asset(id.quoteAsset().value, quoteDecimals)

        fun getAsset(name: String): Asset =
            when (name) {
                baseAsset.name -> baseAsset
                quoteAsset.name -> quoteAsset
                else -> throw RuntimeException("Unexpected asset $name for market $this")
            }
    }

    fun addOrder(
        market: Market,
        amount: BigDecimal,
        price: BigDecimal?,
        wallet: WalletAddress,
        orderType: Order.Type,
        percentage: Int = 0,
    ) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
                    this.marketId = market.id.value
                    this.wallet = wallet.value
                    this.ordersToAdd.add(
                        order {
                            this.guid = Random.nextLong()
                            this.amount = amount.toFundamentalUnits(market.baseDecimals).toIntegerValue()
                            this.price = price?.toDecimalValue() ?: BigDecimal.ZERO.toDecimalValue()
                            this.type = orderType
                            this.percentage = percentage
                        },
                    )
                }
            },
        )

    fun addOrderAndVerifyAccepted(
        market: Market,
        amount: BigDecimal,
        price: BigDecimal?,
        wallet: WalletAddress,
        orderType: Order.Type,
    ): OrderChanged =
        addOrder(market, amount, price, wallet, orderType).let {
            assertEquals(OrderDisposition.Accepted, it.ordersChangedList.first().disposition)
            it.ordersChangedList.first()
        }

    fun changeOrder(
        market: Market,
        guid: OrderGuid,
        amount: BigDecimal,
        price: BigDecimal,
        wallet: WalletAddress,
    ) = sequencer.processRequest(
        sequencerRequest {
            this.guid = UUID.randomUUID().toString()
            this.type = SequencerRequest.Type.ApplyOrderBatch
            this.orderBatch = orderBatch {
                this.marketId = market.id.value
                this.wallet = wallet.value
                this.ordersToChange.add(
                    order {
                        this.guid = guid.value
                        this.amount = amount.toFundamentalUnits(market.baseDecimals).toIntegerValue()
                        this.price = price.toDecimalValue()
                    },
                )
            }
        },
    )

    fun cancelOrder(market: Market, guid: Long, wallet: WalletAddress) =
        sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyOrderBatch
                this.orderBatch = orderBatch {
                    this.marketId = market.id.value
                    this.wallet = wallet.value
                    this.ordersToCancel.add(
                        co.chainring.sequencer.proto.cancelOrder {
                            this.guid = guid
                            this.externalGuid = ""
                        },
                    )
                }
            },
        )

    fun createMarket(marketId: MarketId, tickSize: BigDecimal = "0.05".toBigDecimal(), marketPrice: BigDecimal = "17.525".toBigDecimal(), baseDecimals: Int = 8, quoteDecimals: Int = 18): Market {
        val createMarketResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.AddMarket
                this.addMarket = market {
                    this.guid = UUID.randomUUID().toString()
                    this.marketId = marketId.value
                    this.tickSize = tickSize.toDecimalValue()
                    this.maxLevels = 1000
                    this.maxOrdersPerLevel = 1000
                    this.marketPrice = marketPrice.toDecimalValue()
                    this.baseDecimals = baseDecimals
                    this.quoteDecimals = quoteDecimals
                }
            },
        )
        assertEquals(1, createMarketResponse.marketsCreatedCount)
        val createdMarket = createMarketResponse.marketsCreatedList.first()
        assertEquals(marketId, createdMarket.marketId.toMarketId())
        assertEquals(tickSize, createdMarket.tickSize.toBigDecimal())
        assertEquals(baseDecimals, createdMarket.baseDecimals)
        assertEquals(quoteDecimals, createdMarket.quoteDecimals)

        return Market(marketId, baseDecimals = createdMarket.baseDecimals, quoteDecimals = createdMarket.quoteDecimals)
    }

    fun setFeeRates(feeRates: FeeRates) {
        val response = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.SetFeeRates
                this.feeRates = feeRates {
                    this.maker = feeRates.maker.value
                    this.taker = feeRates.taker.value
                }
            },
        )
        val feeRatesSet = response.feeRatesSet
        assertNotNull(feeRatesSet)
        assertEquals(feeRates.maker.value, feeRatesSet.maker)
        assertEquals(feeRates.taker.value, feeRatesSet.taker)
    }

    fun setWithdrawalFees(fees: List<WithdrawalFee>) {
        val response = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.SetWithdrawalFees
                this.withdrawalFees.addAll(
                    fees.map {
                        co.chainring.sequencer.proto.withdrawalFee {
                            this.asset = it.asset.value
                            this.value = it.fee.toIntegerValue()
                        }
                    },
                )
            },
        )
        val withdrawalFeesSet = response.withdrawalFeesSetList
        assertEquals(withdrawalFeesSet.size, fees.size)
    }

    private fun List<BigInteger>.sum() = this.reduce { a, b -> a + b }
    private fun List<BigDecimal>.sum() = this.reduce { a, b -> a + b }

    fun depositsAndWithdrawals(walletAddress: WalletAddress, asset: Asset, amounts: List<BigDecimal>, expectedAmount: BigDecimal? = amounts.sum(), expectedWithdrawalFees: List<BigInteger> = listOf()): SequencerResponse {
        val depositsAndWithdrawalsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    amounts.filter { a -> a > BigDecimal.ZERO }.let { deposits ->
                        this.deposits.addAll(
                            deposits.map {
                                co.chainring.sequencer.proto.deposit {
                                    this.asset = asset.name
                                    this.wallet = walletAddress.value
                                    this.amount = it.toFundamentalUnits(asset.decimals).toIntegerValue()
                                }
                            },
                        )
                    }
                    amounts.filter { a -> a <= BigDecimal.ZERO }.let { withdrawals ->
                        this.withdrawals.addAll(
                            withdrawals.map {
                                co.chainring.sequencer.proto.withdrawal {
                                    this.asset = asset.name
                                    this.wallet = walletAddress.value
                                    this.amount = (-it).toFundamentalUnits(asset.decimals).toIntegerValue()
                                }
                            },
                        )
                    }
                }
            },
        )
        if (expectedAmount != null) {
            assertEquals(1, depositsAndWithdrawalsResponse.balancesChangedCount)
            val withdrawal = depositsAndWithdrawalsResponse.balancesChangedList.first()
            assertEquals(asset.name, withdrawal.asset)
            assertEquals(walletAddress.value, withdrawal.wallet)
            assertEquals(
                expectedAmount.setScale(asset.decimals),
                withdrawal.delta.toBigInteger().fromFundamentalUnits(asset.decimals),
            )
            assertEquals(
                expectedWithdrawalFees.size,
                depositsAndWithdrawalsResponse.withdrawalsCreatedList.size,
            )
            assertEquals(
                expectedWithdrawalFees.toSet(),
                depositsAndWithdrawalsResponse.withdrawalsCreatedList.map {
                    it.fee.toBigInteger()
                }.toSet(),
            )
        } else {
            assertEquals(0, depositsAndWithdrawalsResponse.balancesChangedCount)
        }
        return depositsAndWithdrawalsResponse
    }

    fun deposit(walletAddress: WalletAddress, asset: Asset, amount: BigDecimal) =
        depositsAndWithdrawals(walletAddress, asset, listOf(amount))

    fun withdrawal(walletAddress: WalletAddress, asset: Asset, amount: BigDecimal, expectedAmount: BigDecimal? = amount, expectedWithdrawalFee: BigInteger = BigInteger.ZERO) =
        depositsAndWithdrawals(walletAddress, asset, listOf(-amount), expectedAmount?.negate(), listOf(expectedWithdrawalFee))

    fun failedWithdrawals(walletAddress: WalletAddress, asset: Asset, amounts: List<BigDecimal>, expectedAmount: BigDecimal? = amounts.sum()): SequencerResponse {
        val failedWithdrawalsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedWithdrawals.addAll(
                        amounts.map {
                            co.chainring.sequencer.proto.failedWithdrawal {
                                this.asset = asset.name
                                this.wallet = walletAddress.value
                                this.amount = it.toFundamentalUnits(asset.decimals).toIntegerValue()
                            }
                        },
                    )
                }
            },
        )
        if (expectedAmount != null) {
            assertEquals(1, failedWithdrawalsResponse.balancesChangedCount)
            val withdrawal = failedWithdrawalsResponse.balancesChangedList.first()
            assertEquals(asset.name, withdrawal.asset)
            assertEquals(walletAddress.value, withdrawal.wallet)
            assertEquals(expectedAmount.setScale(asset.decimals), withdrawal.delta.toBigInteger().fromFundamentalUnits(asset.decimals))
        } else {
            assertEquals(0, failedWithdrawalsResponse.balancesChangedCount)
        }
        return failedWithdrawalsResponse
    }

    fun failedSettlement(buyWalletAddress: WalletAddress, sellWalletAddress: WalletAddress, market: Market, trade: TradeCreated): SequencerResponse {
        val failedSettlementsResponse = sequencer.processRequest(
            sequencerRequest {
                this.guid = UUID.randomUUID().toString()
                this.type = SequencerRequest.Type.ApplyBalanceBatch
                this.balanceBatch = balanceBatch {
                    this.guid = UUID.randomUUID().toString()
                    this.failedSettlements.add(
                        co.chainring.sequencer.proto.failedSettlement {
                            this.buyWallet = buyWalletAddress.value
                            this.sellWallet = sellWalletAddress.value
                            this.marketId = market.id.value
                            this.trade = trade
                        },
                    )
                }
            },
        )
        return failedSettlementsResponse
    }
}
