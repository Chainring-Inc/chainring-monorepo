import { ConfigurationApiResponseSchema, Trade } from 'apiClient'
import {
  OrderTradesGroup,
  rollupTrades
} from 'components/Screens/HomeScreen/OrdersAndTradesWidget/tradeRollup'
import TradingSymbols from 'tradingSymbols'
import Markets from 'markets'
import TradingSymbol from 'tradingSymbol'
import Decimal from 'decimal.js'
import { scaledDecimalToBigint } from 'utils/pricesUtils'
import { expect } from 'vitest'

describe('rollupTrades', () => {
  class SymbolAmount {
    amount: Decimal
    symbol: TradingSymbol

    constructor(amount: Decimal | string | number, symbol: TradingSymbol) {
      if (typeof amount === 'string' || typeof amount == 'number') {
        this.amount = new Decimal(amount)
      } else {
        this.amount = amount
      }
      this.symbol = symbol
    }

    asBigInt(): bigint {
      return scaledDecimalToBigint(this.amount, this.symbol.decimals)
    }
  }

  function verifyTradesRollup({
    markets,
    trades,
    expectedResult
  }: {
    markets: Markets
    trades: Trade[]
    expectedResult: OrderTradesGroup[]
  }) {
    expect(rollupTrades(trades, markets)).toEqual(expectedResult)
  }

  const config = ConfigurationApiResponseSchema.parse({
    chains: [
      {
        id: 1337,
        name: 'localhost:8545',
        symbols: [
          {
            name: 'BTC:1337',
            description: 'Bitcoin',
            contractAddress: null,
            decimals: 18,
            faucetSupported: true,
            iconUrl: '',
            withdrawalFee: '20000000000000'
          },
          {
            name: 'DAI:1337',
            description: 'Dai',
            contractAddress: '0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0',
            decimals: 18,
            faucetSupported: true,
            iconUrl: '',
            withdrawalFee: '1000000000000000000'
          },
          {
            name: 'USDC:1337',
            description: 'USD Coin',
            contractAddress: '0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512',
            decimals: 6,
            faucetSupported: true,
            iconUrl: '',
            withdrawalFee: '1000000'
          }
        ],
        contracts: [],
        jsonRpcUrl: '',
        blockExplorerUrl: '',
        blockExplorerNetName: '',
        networkType: 'Evm'
      }
    ],
    markets: [
      {
        id: 'BTC:1337/USDC:1337',
        baseSymbol: 'BTC:1337',
        baseDecimals: 18,
        quoteSymbol: 'USDC:1337',
        quoteDecimals: 6,
        tickSize: '25.000000000000000000',
        lastPrice: '61000.000000000000000000',
        minFee: '20000'
      },
      {
        id: 'USDC:1337/DAI:1337',
        baseSymbol: 'USDC:1337',
        baseDecimals: 6,
        quoteSymbol: 'DAI:1337',
        quoteDecimals: 18,
        tickSize: '0.010000000000000000',
        lastPrice: '2.160000000000000000',
        minFee: '20000000000000000'
      }
    ],
    feeRates: {
      maker: 0n,
      taker: 0n
    }
  })

  const symbols = TradingSymbols.fromConfig(config)
  const markets = new Markets(config.markets, symbols, false)

  const BTC = symbols.getByName('BTC:1337')
  const USDC = symbols.getByName('USDC:1337')
  const DAI = symbols.getByName('DAI:1337')
  const btcUsdcMarketId = 'BTC:1337/USDC:1337'
  const usdcDaiMarketId = 'USDC:1337/DAI:1337'

  it('groups taker trades by order', () => {
    verifyTradesRollup({
      markets,
      trades: [
        // group 1: USDC -> BTC
        {
          id: 'trade_01',
          timestamp: new Date('2024-07-17T18:00:00.000Z'),
          orderId: 'order_01',
          executionRole: 'Taker',
          counterOrderId: 'order_02',
          marketId: btcUsdcMarketId,
          side: 'Buy',
          amount: new SymbolAmount(0.1, BTC).asBigInt(),
          price: new Decimal(50000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        // group 2: BTC -> USDC
        {
          id: 'trade_02',
          timestamp: new Date('2024-07-17T18:00:01.000Z'),
          orderId: 'order_03',
          executionRole: 'Taker',
          counterOrderId: 'order_04',
          marketId: btcUsdcMarketId,
          side: 'Sell',
          amount: new SymbolAmount(0.1, BTC).asBigInt(),
          price: new Decimal(50000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        // group 3: USDC -> BTC
        {
          id: 'trade_03',
          timestamp: new Date('2024-07-17T18:00:02.000Z'),
          orderId: 'order_05',
          executionRole: 'Taker',
          counterOrderId: 'order_06',
          marketId: btcUsdcMarketId,
          side: 'Buy',
          amount: new SymbolAmount(0.1, BTC).asBigInt(),
          price: new Decimal(50000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        {
          id: 'trade_04',
          timestamp: new Date('2024-07-17T18:00:02.000Z'),
          orderId: 'order_05',
          executionRole: 'Taker',
          counterOrderId: 'order_07',
          marketId: btcUsdcMarketId,
          side: 'Buy',
          amount: new SymbolAmount(0.2, BTC).asBigInt(),
          price: new Decimal(25000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        // group 4: BTC -> USDC
        {
          id: 'trade_05',
          timestamp: new Date('2024-07-17T18:00:03.000Z'),
          orderId: 'order_08',
          executionRole: 'Taker',
          counterOrderId: 'order_09',
          marketId: btcUsdcMarketId,
          side: 'Sell',
          amount: new SymbolAmount(0.2, BTC).asBigInt(),
          price: new Decimal(50000),
          feeAmount: new SymbolAmount(10, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        {
          id: 'trade_06',
          timestamp: new Date('2024-07-17T18:00:03.000Z'),
          orderId: 'order_08',
          executionRole: 'Taker',
          counterOrderId: 'order_10',
          marketId: btcUsdcMarketId,
          side: 'Sell',
          amount: new SymbolAmount(0.2, BTC).asBigInt(),
          price: new Decimal(25000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        // group 5: DAI -> BTC (back to back order)
        {
          id: 'trade_07',
          timestamp: new Date('2024-07-17T18:00:04.000Z'),
          orderId: 'order_11',
          executionRole: 'Taker',
          counterOrderId: 'order_12',
          marketId: usdcDaiMarketId,
          side: 'Buy',
          amount: new SymbolAmount(5000, USDC).asBigInt(),
          price: new Decimal(2),
          feeAmount: new SymbolAmount(10, DAI).asBigInt(),
          feeSymbol: DAI.name,
          settlementStatus: 'Completed'
        },
        {
          id: 'trade_08',
          timestamp: new Date('2024-07-17T18:00:04.000Z'),
          orderId: 'order_11',
          executionRole: 'Taker',
          counterOrderId: 'order_13',
          marketId: usdcDaiMarketId,
          side: 'Buy',
          amount: new SymbolAmount(2500, USDC).asBigInt(),
          price: new Decimal(4),
          feeAmount: new SymbolAmount(10, DAI).asBigInt(),
          feeSymbol: DAI.name,
          settlementStatus: 'Completed'
        },
        {
          id: 'trade_09',
          timestamp: new Date('2024-07-17T18:00:04.000Z'),
          orderId: 'order_11',
          executionRole: 'Taker',
          counterOrderId: 'order_14',
          marketId: btcUsdcMarketId,
          side: 'Buy',
          amount: new SymbolAmount(0.1, BTC).asBigInt(),
          price: new Decimal(50_000),
          feeAmount: 0n,
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        {
          id: 'trade_10',
          timestamp: new Date('2024-07-17T18:00:04.000Z'),
          orderId: 'order_11',
          executionRole: 'Taker',
          counterOrderId: 'order_15',
          marketId: btcUsdcMarketId,
          side: 'Buy',
          amount: new SymbolAmount(0.1, BTC).asBigInt(),
          price: new Decimal(25_000),
          feeAmount: 0n,
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        }
      ],
      expectedResult: [
        // group 1: USDC -> BTC
        {
          id: 'taker:order_01',
          timestamp: new Date('2024-07-17T18:00:00.000Z'),
          sellSymbol: USDC,
          buySymbol: BTC,
          aggregatedAmount: new SymbolAmount(5000, USDC).asBigInt(),
          aggregatedPrice: new Decimal(0.00002),
          priceDecimalPlaces: 6,
          aggregatedFeeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC,
          settlementStatus: 'Completed',
          trades: [
            {
              id: 'trade_01',
              orderId: 'order_01',
              marketId: btcUsdcMarketId,
              sellSymbol: USDC,
              buySymbol: BTC,
              amount: new SymbolAmount(5000, USDC).asBigInt(),
              price: new Decimal(0.00002),
              priceDecimalPlaces: 6,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            }
          ],
          expanded: false
        },
        // group 2: BTC -> USDC
        {
          id: 'taker:order_03',
          timestamp: new Date('2024-07-17T18:00:01.000Z'),
          sellSymbol: BTC,
          buySymbol: USDC,
          aggregatedAmount: new SymbolAmount(0.1, BTC).asBigInt(),
          aggregatedPrice: new Decimal(50000),
          priceDecimalPlaces: 1,
          aggregatedFeeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC,
          settlementStatus: 'Completed',
          trades: [
            {
              id: 'trade_02',
              orderId: 'order_03',
              marketId: btcUsdcMarketId,
              sellSymbol: BTC,
              buySymbol: USDC,
              amount: new SymbolAmount(0.1, BTC).asBigInt(),
              price: new Decimal(50000),
              priceDecimalPlaces: 1,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            }
          ],
          expanded: false
        },
        // group 3: USDC -> BTC
        {
          id: 'taker:order_05',
          timestamp: new Date('2024-07-17T18:00:02.000Z'),
          sellSymbol: USDC,
          buySymbol: BTC,
          aggregatedAmount: new SymbolAmount(10000, USDC).asBigInt(),
          aggregatedPrice: new Decimal(0.00003),
          priceDecimalPlaces: 6,
          aggregatedFeeAmount: new SymbolAmount(10, USDC).asBigInt(),
          feeSymbol: USDC,
          settlementStatus: 'Completed',
          trades: [
            {
              id: 'trade_03',
              orderId: 'order_05',
              marketId: btcUsdcMarketId,
              sellSymbol: USDC,
              buySymbol: BTC,
              amount: new SymbolAmount(5000, USDC).asBigInt(),
              price: new Decimal(0.00002),
              priceDecimalPlaces: 6,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            },
            {
              id: 'trade_04',
              orderId: 'order_05',
              marketId: btcUsdcMarketId,
              sellSymbol: USDC,
              buySymbol: BTC,
              amount: new SymbolAmount(5000, USDC).asBigInt(),
              price: new Decimal(0.00004),
              priceDecimalPlaces: 6,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            }
          ],
          expanded: false
        },
        // group 4: BTC -> USDC
        {
          id: 'taker:order_08',
          timestamp: new Date('2024-07-17T18:00:03.000Z'),
          sellSymbol: BTC,
          buySymbol: USDC,
          aggregatedAmount: new SymbolAmount(0.4, BTC).asBigInt(),
          aggregatedPrice: new Decimal(37500),
          priceDecimalPlaces: 1,
          aggregatedFeeAmount: new SymbolAmount(15, USDC).asBigInt(),
          feeSymbol: USDC,
          settlementStatus: 'Completed',
          trades: [
            {
              id: 'trade_05',
              orderId: 'order_08',
              marketId: btcUsdcMarketId,
              sellSymbol: BTC,
              buySymbol: USDC,
              amount: new SymbolAmount(0.2, BTC).asBigInt(),
              price: new Decimal(50_000),
              priceDecimalPlaces: 1,
              feeAmount: new SymbolAmount(10, USDC).asBigInt(),
              feeSymbol: USDC
            },
            {
              id: 'trade_06',
              orderId: 'order_08',
              marketId: btcUsdcMarketId,
              sellSymbol: BTC,
              buySymbol: USDC,
              amount: new SymbolAmount(0.2, BTC).asBigInt(),
              price: new Decimal(25_000),
              priceDecimalPlaces: 1,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            }
          ],
          expanded: false
        },
        // group 5: DAI -> BTC (back to back order)
        {
          id: 'taker:order_11',
          timestamp: new Date('2024-07-17T18:00:04.000Z'),
          sellSymbol: DAI,
          buySymbol: BTC,
          aggregatedAmount: new SymbolAmount(20_000, DAI).asBigInt(),
          aggregatedPrice: new Decimal('0.00000999999999999975'),
          priceDecimalPlaces: 6,
          aggregatedFeeAmount: new SymbolAmount(20, DAI).asBigInt(),
          feeSymbol: DAI,
          settlementStatus: 'Completed',
          trades: [
            {
              id: 'trade_07',
              orderId: 'order_11',
              marketId: usdcDaiMarketId,
              sellSymbol: DAI,
              buySymbol: USDC,
              amount: new SymbolAmount(10_000, DAI).asBigInt(),
              price: new Decimal(0.5),
              priceDecimalPlaces: 6,
              feeAmount: new SymbolAmount(10, DAI).asBigInt(),
              feeSymbol: DAI
            },
            {
              id: 'trade_08',
              orderId: 'order_11',
              marketId: usdcDaiMarketId,
              sellSymbol: DAI,
              buySymbol: USDC,
              amount: new SymbolAmount(10_000, DAI).asBigInt(),
              price: new Decimal(0.25),
              priceDecimalPlaces: 6,
              feeAmount: new SymbolAmount(10, DAI).asBigInt(),
              feeSymbol: DAI
            },
            {
              id: 'trade_09',
              orderId: 'order_11',
              marketId: btcUsdcMarketId,
              sellSymbol: USDC,
              buySymbol: BTC,
              amount: new SymbolAmount(5000, USDC).asBigInt(),
              price: new Decimal(0.00002),
              priceDecimalPlaces: 6,
              feeAmount: new SymbolAmount(0, USDC).asBigInt(),
              feeSymbol: USDC
            },
            {
              id: 'trade_10',
              orderId: 'order_11',
              marketId: btcUsdcMarketId,
              sellSymbol: USDC,
              buySymbol: BTC,
              amount: new SymbolAmount(2500, USDC).asBigInt(),
              price: new Decimal(0.00004),
              priceDecimalPlaces: 6,
              feeAmount: new SymbolAmount(0, USDC).asBigInt(),
              feeSymbol: USDC
            }
          ],
          expanded: false
        }
      ]
    })
  })

  it('groups maker trades by taker order', () => {
    verifyTradesRollup({
      markets,
      trades: [
        // group 1: BTC -> USDC, maker order_01 and order_02 were matched with taker order_03
        {
          id: 'trade_01',
          timestamp: new Date('2024-07-17T18:00:00.000Z'),
          orderId: 'order_01',
          executionRole: 'Maker',
          counterOrderId: 'order_03',
          marketId: btcUsdcMarketId,
          side: 'Sell',
          amount: new SymbolAmount(0.1, BTC).asBigInt(),
          price: new Decimal(50000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        {
          id: 'trade_02',
          timestamp: new Date('2024-07-17T18:00:00.000Z'),
          orderId: 'order_02',
          executionRole: 'Maker',
          counterOrderId: 'order_03',
          marketId: btcUsdcMarketId,
          side: 'Sell',
          amount: new SymbolAmount(0.2, BTC).asBigInt(),
          price: new Decimal(25000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        // group 1: BTC -> USDC, maker order_01 and order_02 were matched with taker order_04
        {
          id: 'trade_03',
          timestamp: new Date('2024-07-17T18:00:01.000Z'),
          orderId: 'order_01',
          executionRole: 'Maker',
          counterOrderId: 'order_04',
          marketId: btcUsdcMarketId,
          side: 'Sell',
          amount: new SymbolAmount(0.1, BTC).asBigInt(),
          price: new Decimal(50000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        },
        {
          id: 'trade_04',
          timestamp: new Date('2024-07-17T18:00:01.000Z'),
          orderId: 'order_02',
          executionRole: 'Maker',
          counterOrderId: 'order_04',
          marketId: btcUsdcMarketId,
          side: 'Sell',
          amount: new SymbolAmount(0.2, BTC).asBigInt(),
          price: new Decimal(25000),
          feeAmount: new SymbolAmount(5, USDC).asBigInt(),
          feeSymbol: USDC.name,
          settlementStatus: 'Completed'
        }
      ],
      expectedResult: [
        // group 1: BTC -> USDC, maker order_01 and order_02 were matched with taker order_03
        {
          id: 'maker:order_03',
          timestamp: new Date('2024-07-17T18:00:00.000Z'),
          sellSymbol: BTC,
          buySymbol: USDC,
          aggregatedAmount: new SymbolAmount(0.3, BTC).asBigInt(),
          aggregatedPrice: new Decimal('33333.333333'),
          priceDecimalPlaces: 1,
          aggregatedFeeAmount: new SymbolAmount(10, USDC).asBigInt(),
          feeSymbol: USDC,
          settlementStatus: 'Completed',
          trades: [
            {
              id: 'trade_01',
              orderId: 'order_01',
              marketId: btcUsdcMarketId,
              sellSymbol: BTC,
              buySymbol: USDC,
              amount: new SymbolAmount(0.1, BTC).asBigInt(),
              price: new Decimal(50_000),
              priceDecimalPlaces: 1,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            },
            {
              id: 'trade_02',
              orderId: 'order_02',
              marketId: btcUsdcMarketId,
              sellSymbol: BTC,
              buySymbol: USDC,
              amount: new SymbolAmount(0.2, BTC).asBigInt(),
              price: new Decimal(25_000),
              priceDecimalPlaces: 1,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            }
          ],
          expanded: false
        },
        // group 1: BTC -> USDC, maker order_01 and order_02 were matched with taker order_04
        {
          id: 'maker:order_04',
          timestamp: new Date('2024-07-17T18:00:01.000Z'),
          sellSymbol: BTC,
          buySymbol: USDC,
          aggregatedAmount: new SymbolAmount(0.3, BTC).asBigInt(),
          aggregatedPrice: new Decimal('33333.333333'),
          priceDecimalPlaces: 1,
          aggregatedFeeAmount: new SymbolAmount(10, USDC).asBigInt(),
          feeSymbol: USDC,
          settlementStatus: 'Completed',
          trades: [
            {
              id: 'trade_03',
              orderId: 'order_01',
              marketId: btcUsdcMarketId,
              sellSymbol: BTC,
              buySymbol: USDC,
              amount: new SymbolAmount(0.1, BTC).asBigInt(),
              price: new Decimal(50_000),
              priceDecimalPlaces: 1,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            },
            {
              id: 'trade_04',
              orderId: 'order_02',
              marketId: btcUsdcMarketId,
              sellSymbol: BTC,
              buySymbol: USDC,
              amount: new SymbolAmount(0.2, BTC).asBigInt(),
              price: new Decimal(25_000),
              priceDecimalPlaces: 1,
              feeAmount: new SymbolAmount(5, USDC).asBigInt(),
              feeSymbol: USDC
            }
          ],
          expanded: false
        }
      ]
    })
  })
})
