import Markets, { Market } from 'markets'
import { ChangeEvent, useCallback, useEffect, useMemo, useState } from 'react'
import TradingSymbol from 'tradingSymbol'
import { apiClient, Balance, FeeRates, OrderSide } from 'apiClient'
import { useWebsocketSubscription } from 'contexts/websocket'
import {
  balancesTopic,
  limitsTopic,
  OrderBook,
  orderBookTopic,
  Publishable
} from 'websocketMessages'
import { Address, formatUnits, parseUnits } from 'viem'
import { calculateFee, calculateNotional } from 'utils'
import {
  bigintToScaledDecimal,
  getMarketPrice,
  scaledDecimalToBigint
} from 'utils/pricesUtils'
import { useMutation, UseMutationResult } from '@tanstack/react-query'
import { addressZero, generateOrderNonce, getDomain } from 'utils/eip712'
import Decimal from 'decimal.js'
import { isErrorFromAlias } from '@zodios/core'
import useAmountInputState from 'hooks/useAmountInputState'
import { useConfig, useSignTypedData, BaseError as WagmiError } from 'wagmi'

export type SwapRender = {
  topBalance: Balance | undefined
  topSymbol: TradingSymbol
  bottomBalance: Balance | undefined
  bottomSymbol: TradingSymbol
  mutation: UseMutationResult<
    { orderId: string; requestStatus: 'Rejected' | 'Accepted' },
    Error,
    void,
    unknown
  >
  buyAmountInputValue: string
  sellAmountInputValue: string
  side: OrderSide
  handleQuoteAmountChange: (e: ChangeEvent<HTMLInputElement>) => void
  handleBaseAmountChange: (e: ChangeEvent<HTMLInputElement>) => void
  sellAssetsNeeded: bigint
  handleTopSymbolChange: (newSymbol: TradingSymbol) => void
  handleBottomSymbolChange: (newSymbol: TradingSymbol) => void
  handleChangeSide: () => void
  isLimitOrder: boolean
  handleMarketOrderFlagChange: (e: ChangeEvent<HTMLInputElement>) => void
  limitPriceInputValue: string
  limitPrice: bigint
  handlePriceChange: (e: ChangeEvent<HTMLInputElement>) => void
  setPriceFromMarketPrice: (incrementDivisor?: bigint) => void
  noPriceFound: boolean
  canSubmit: boolean
  getMarketPrice: (side: OrderSide, amount: bigint) => bigint | undefined
  quoteDecimals: number
}

export function SwapInternals({
  markets,
  exchangeContractAddress,
  walletAddress,
  feeRates,
  onMarketChange,
  onSideChange,
  isLimitOrder: initialIsLimitOrder,
  Renderer
}: {
  markets: Markets
  exchangeContractAddress?: Address
  walletAddress?: Address
  feeRates: FeeRates
  onMarketChange: (m: Market) => void
  onSideChange: (s: OrderSide) => void
  isLimitOrder: boolean
  Renderer: (r: SwapRender) => JSX.Element
}) {
  const [market, setMarket] = useState<Market>(markets.first()!)
  const [topSymbol, setTopSymbol] = useState<TradingSymbol>(
    markets.first()!.quoteSymbol
  )
  const [bottomSymbol, setBottomSymbol] = useState<TradingSymbol>(
    markets.first()!.baseSymbol
  )
  const [side, setSide] = useState<OrderSide>('Buy')
  const [balances, setBalances] = useState<Balance[]>(() => [])

  useEffect(() => {
    const selectedMarket = window.sessionStorage.getItem('market')
    const selectedSide = window.sessionStorage.getItem('side')
    if (selectedMarket && selectedSide) {
      const market = markets.findById(selectedMarket)
      if (market) {
        setMarket(market)
        if (selectedSide === 'Buy') {
          setSide('Buy')
          setTopSymbol(market.quoteSymbol)
          setBottomSymbol(market.baseSymbol)
        } else if (selectedSide === 'Sell') {
          setSide('Sell')
          setTopSymbol(market.baseSymbol)
          setBottomSymbol(market.quoteSymbol)
        }
      }
    }
  }, [markets])

  useWebsocketSubscription({
    topics: useMemo(() => [balancesTopic], []),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'Balances') {
        setBalances(message.balances)
      }
    }, []),
    onUnsubscribe: useCallback(() => {
      setBalances([])
    }, [])
  })

  const [topBalance, bottomBalance] = useMemo(() => {
    const topBalance = balances.find(
      (balance) => balance.symbol === topSymbol.name
    )
    const bottomBalance = balances.find(
      (balance) => balance.symbol === bottomSymbol.name
    )
    return [topBalance, bottomBalance]
  }, [topSymbol, bottomSymbol, balances])

  const config = useConfig()
  const { signTypedDataAsync } = useSignTypedData()

  const [baseSymbol, quoteSymbol] = useMemo(() => {
    return [market.baseSymbol, market.quoteSymbol]
  }, [market])

  const {
    inputValue: limitPriceInputValue,
    setInputValue: setPriceInputValue,
    valueInFundamentalUnits: priceInput
  } = useAmountInputState({
    initialInputValue: '',
    decimals: quoteSymbol.decimals
  })

  const {
    inputValue: baseAmountInputValue,
    setInputValue: setBaseAmountInputValue,
    valueInFundamentalUnits: baseAmount
  } = useAmountInputState({
    initialInputValue: '',
    decimals: baseSymbol.decimals
  })

  const {
    inputValue: quoteAmountInputValue,
    setInputValue: setQuoteAmountInputValue,
    valueInFundamentalUnits: quoteAmount
  } = useAmountInputState({
    initialInputValue: '',
    decimals: quoteSymbol.decimals
  })

  const [isLimitOrder, setIsLimitOrder] = useState(initialIsLimitOrder)

  const limitPrice = useMemo(() => {
    const tickAsInt = parseUnits(
      market.tickSize.toString(),
      quoteSymbol.decimals
    )
    if (side === 'Buy') {
      return priceInput - (priceInput % tickAsInt)
    } else {
      if (priceInput === 0n) {
        return 0n
      } else {
        const invertedPrice = scaledDecimalToBigint(
          new Decimal(1).div(
            bigintToScaledDecimal(priceInput, quoteSymbol.decimals)
          ),
          quoteSymbol.decimals
        )
        return invertedPrice - (invertedPrice % tickAsInt)
      }
    }
  }, [priceInput, side, market, quoteSymbol])

  const [orderBook, setOrderBook] = useState<OrderBook | undefined>(undefined)
  useWebsocketSubscription({
    topics: useMemo(() => [orderBookTopic(market.id)], [market.id]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'OrderBook') {
        setOrderBook(message)
      }
    }, [])
  })

  const [baseLimit, setBaseLimit] = useState<bigint | undefined>(undefined)
  const [quoteLimit, setQuoteLimit] = useState<bigint | undefined>(undefined)

  useWebsocketSubscription({
    topics: useMemo(() => [limitsTopic(market.id)], [market.id]),
    handler: useCallback((message: Publishable) => {
      if (message.type === 'Limits') {
        setBaseLimit(message.base)
        setQuoteLimit(message.quote)
      }
    }, []),
    onUnsubscribe: useCallback(() => {
      setBaseLimit(undefined)
      setQuoteLimit(undefined)
    }, [])
  })

  const marketPrice = useMemo(() => {
    if (orderBook === undefined) return 0n
    return getMarketPrice(side, baseAmount, market, orderBook)
  }, [side, baseAmount, orderBook, market])

  const { notional, fee } = useMemo(() => {
    if (isLimitOrder) {
      const notional = calculateNotional(
        limitPrice || marketPrice,
        baseAmount,
        baseSymbol
      )
      return {
        notional,
        fee: calculateFee(notional, feeRates.maker)
      }
    } else {
      const notional = calculateNotional(marketPrice, baseAmount, baseSymbol)
      return {
        notional,
        fee: calculateFee(notional, feeRates.taker)
      }
    }
  }, [limitPrice, marketPrice, baseAmount, isLimitOrder, baseSymbol, feeRates])

  const [baseAmountManuallyChanged, setBaseAmountManuallyChanged] =
    useState(false)
  const [quoteAmountManuallyChanged, setQuoteAmountManuallyChanged] =
    useState(false)
  const [noPriceFound, setNoPriceFound] = useState(false)

  function handleBaseAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(true)
    setBaseAmountInputValue(e.target.value)

    mutation.reset()
  }

  useEffect(() => {
    if (baseAmountManuallyChanged) {
      if (orderBook !== undefined) {
        const indicativePrice =
          isLimitOrder && limitPrice !== 0n
            ? limitPrice
            : getMarketPrice(side, baseAmount, market, orderBook)
        if (indicativePrice === 0n) {
          setNoPriceFound(true)
        } else {
          setNoPriceFound(false)
          const notional =
            (baseAmount * indicativePrice) /
            BigInt(Math.pow(10, baseSymbol.decimals))
          setQuoteAmountInputValue(formatUnits(notional, quoteSymbol.decimals))
        }
      }
    }
  }, [
    baseAmount,
    baseAmountManuallyChanged,
    setQuoteAmountInputValue,
    orderBook,
    market,
    baseSymbol,
    quoteSymbol,
    side,
    isLimitOrder,
    limitPrice
  ])

  useEffect(() => {
    if (quoteAmountManuallyChanged) {
      if (orderBook !== undefined) {
        const indicativePrice =
          isLimitOrder && limitPrice !== 0n
            ? limitPrice
            : getMarketPrice(
                side,
                (baseAmount ?? 1) || BigInt(1),
                market,
                orderBook
              )
        if (indicativePrice === 0n) {
          setNoPriceFound(true)
        } else {
          setNoPriceFound(false)
          const quantity =
            (quoteAmount * BigInt(Math.pow(10, baseSymbol.decimals))) /
            indicativePrice
          setBaseAmountInputValue(formatUnits(quantity, baseSymbol.decimals))
        }
      }
    }
  }, [
    quoteAmount,
    quoteAmountManuallyChanged,
    setBaseAmountInputValue,
    orderBook,
    market,
    baseAmount,
    baseSymbol,
    side,
    isLimitOrder,
    limitPrice
  ])

  function handleQuoteAmountChange(e: ChangeEvent<HTMLInputElement>) {
    setBaseAmountManuallyChanged(false)
    setQuoteAmountManuallyChanged(true)
    setQuoteAmountInputValue(e.target.value)
    mutation.reset()
  }

  function clearAmountFields() {
    setQuoteAmountManuallyChanged(false)
    setBaseAmountManuallyChanged(false)
    setBaseAmountInputValue('')
    setQuoteAmountInputValue('')
    setPriceInputValue('')
  }

  function saveMarketAndSide(market: Market, side: OrderSide) {
    setSide(side)
    setMarket(market)
    window.sessionStorage.setItem('market', market.id)
    window.sessionStorage.setItem('side', side)
    onMarketChange(market)
    onSideChange(side)
  }

  function handleTopSymbolChange(newSymbol: TradingSymbol) {
    const newMarket = getMarketForSideAndSymbol(side, newSymbol, bottomSymbol)
    setTopSymbol(newSymbol)
    if (newMarket.quoteSymbol.name === newSymbol.name) {
      saveMarketAndSide(newMarket, 'Buy')
      setBottomSymbol(newMarket.baseSymbol)
    } else {
      saveMarketAndSide(newMarket, 'Sell')
      setBottomSymbol(newMarket.quoteSymbol)
    }
    clearAmountFields()
    mutation.reset()
  }

  function handleBottomSymbolChange(newSymbol: TradingSymbol) {
    const newMarket = getMarketForSideAndSymbol(
      side === 'Sell' ? 'Buy' : 'Sell',
      newSymbol,
      topSymbol
    )
    setBottomSymbol(newSymbol)
    if (newMarket.quoteSymbol.name === newSymbol.name) {
      saveMarketAndSide(newMarket, 'Sell')
      setTopSymbol(newMarket.baseSymbol)
    } else {
      setTopSymbol(newMarket.quoteSymbol)
      saveMarketAndSide(newMarket, 'Buy')
    }
    clearAmountFields()
    mutation.reset()
  }

  function handleChangeSide() {
    const newSide = side === 'Buy' ? 'Sell' : 'Buy'
    saveMarketAndSide(market, newSide)
    const tempSymbol = topSymbol
    setTopSymbol(bottomSymbol)
    setBottomSymbol(tempSymbol)
    setPriceInputValue('')
    mutation.reset()
  }

  function handlePriceChange(e: ChangeEvent<HTMLInputElement>) {
    setPriceInputValue(e.target.value)
    mutation.reset()
  }

  function handleMarketOrderFlagChange(e: ChangeEvent<HTMLInputElement>) {
    setPriceInputValue('')
    setIsLimitOrder(e.target.checked)
    mutation.reset()
  }

  function setPriceFromMarketPrice(incrementDivisor?: bigint) {
    if (side === 'Sell') {
      let rawPrice
      if (incrementDivisor) {
        rawPrice = parseFloat(
          formatUnits(
            marketPrice + marketPrice / incrementDivisor,
            quoteSymbol.decimals
          )
        )
      } else {
        rawPrice = parseFloat(formatUnits(marketPrice, quoteSymbol.decimals))
      }
      const invertedPrice = 1.0 / rawPrice
      setPriceInputValue(invertedPrice.toFixed(6))
    } else {
      let rawPrice
      if (incrementDivisor) {
        rawPrice = parseFloat(
          formatUnits(
            marketPrice - marketPrice / incrementDivisor,
            quoteSymbol.decimals
          )
        )
      } else {
        rawPrice = parseFloat(formatUnits(marketPrice, quoteSymbol.decimals))
      }
      setPriceInputValue(rawPrice.toFixed(market.tickSize.decimalPlaces()))
    }
  }

  const mutation = useMutation({
    mutationFn: async () => {
      try {
        const nonce = generateOrderNonce()
        const signature = await signTypedDataAsync({
          types: {
            EIP712Domain: [
              { name: 'name', type: 'string' },
              { name: 'version', type: 'string' },
              { name: 'chainId', type: 'uint256' },
              { name: 'verifyingContract', type: 'address' }
            ],
            Order: [
              { name: 'sender', type: 'address' },
              { name: 'baseToken', type: 'address' },
              { name: 'quoteToken', type: 'address' },
              { name: 'amount', type: 'int256' },
              { name: 'price', type: 'uint256' },
              { name: 'nonce', type: 'int256' }
            ]
          },
          domain: getDomain(exchangeContractAddress!, config.state.chainId),
          primaryType: 'Order',
          message: {
            sender: walletAddress!,
            baseToken: baseSymbol.contractAddress ?? addressZero,
            quoteToken: quoteSymbol.contractAddress ?? addressZero,
            amount: side == 'Buy' ? baseAmount : -baseAmount,
            price: isLimitOrder ? limitPrice : 0n,
            nonce: BigInt('0x' + nonce)
          }
        })

        let response
        if (isLimitOrder) {
          response = await apiClient.createOrder({
            nonce: nonce,
            marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
            type: 'limit',
            side: side,
            amount: baseAmount,
            price: new Decimal(formatUnits(limitPrice, quoteSymbol.decimals)),
            signature: signature,
            verifyingChainId: config.state.chainId
          })
        } else {
          response = await apiClient.createOrder({
            nonce: nonce,
            marketId: `${baseSymbol.name}/${quoteSymbol.name}`,
            type: 'market',
            side: side,
            amount: baseAmount,
            signature: signature,
            verifyingChainId: config.state.chainId
          })
        }
        clearAmountFields()
        return response
      } catch (error) {
        throw Error(
          isErrorFromAlias(apiClient.api, 'createOrder', error)
            ? error.response.data.errors[0].displayMessage
            : (error as WagmiError).shortMessage || 'Something went wrong'
        )
      }
    },
    onSuccess: () => {
      setTimeout(mutation.reset, 3000)
    }
  })

  const canSubmit = useMemo(() => {
    if (mutation.isPending) return false
    if (baseAmount <= 0n) return false
    if (limitPrice <= 0n && isLimitOrder) return false

    if (side == 'Buy' && notional + fee > (quoteLimit || 0n)) return false
    if (side == 'Sell' && baseAmount > (baseLimit || 0n)) return false

    if (noPriceFound) return false

    return true
  }, [
    mutation.isPending,
    side,
    baseAmount,
    limitPrice,
    notional,
    isLimitOrder,
    quoteLimit,
    baseLimit,
    noPriceFound,
    fee
  ])

  const [buyAmountInputValue, sellAmountInputValue] = useMemo(() => {
    return side === 'Buy'
      ? [baseAmountInputValue, quoteAmountInputValue]
      : [quoteAmountInputValue, baseAmountInputValue]
  }, [side, baseAmountInputValue, quoteAmountInputValue])

  function getMarketForSideAndSymbol(
    side: OrderSide,
    newSymbol: TradingSymbol,
    otherSymbol: TradingSymbol
  ): Market {
    // look for a market where the new symbol is available on the desired swap direction and has the existing other symbol
    return (
      markets.find(
        (m) =>
          (side === 'Sell' &&
            m.quoteSymbol === newSymbol &&
            m.baseSymbol === otherSymbol) ||
          (side === 'Buy' &&
            m.baseSymbol === newSymbol &&
            m.quoteSymbol === otherSymbol)
      ) ??
      // failing that, where the new symbol is available on the other swap direction and has the existing buy symbol
      markets.find(
        (m) =>
          (side === 'Sell' &&
            m.baseSymbol === newSymbol &&
            m.quoteSymbol === otherSymbol) ||
          (side === 'Buy' &&
            m.quoteSymbol === newSymbol &&
            m.baseSymbol === otherSymbol)
      ) ??
      // failing that, one where the new symbol is available on the desired swap direction, even if the existing buy symbol is not
      markets.find(
        (m) =>
          (side === 'Sell' && m.quoteSymbol === newSymbol) ||
          (side === 'Buy' && m.baseSymbol === newSymbol)
      ) ??
      // failing that, one where the new symbol is available on the other swap direction (this one must succeed)
      markets.find(
        (m) =>
          (side === 'Sell' && m.baseSymbol === newSymbol) ||
          (side === 'Buy' && m.quoteSymbol === newSymbol)
      )!
    )
  }

  const sellAssetsNeeded = useMemo(() => {
    return topSymbol.name === quoteSymbol.name
      ? notional + fee - (quoteLimit || 0n)
      : baseAmount - (baseLimit || 0n)
  }, [topSymbol, quoteSymbol, notional, fee, quoteLimit, baseAmount, baseLimit])

  function getSimulatedPrice(
    side: OrderSide,
    amount: bigint
  ): bigint | undefined {
    if (orderBook) {
      const marketPrice = getMarketPrice(side, amount, market, orderBook)
      if (side === 'Sell' && marketPrice !== 0n) {
        return scaledDecimalToBigint(
          new Decimal(1).div(
            bigintToScaledDecimal(marketPrice, quoteSymbol.decimals)
          ),
          quoteSymbol.decimals
        )
      }
      return marketPrice
    }
    return
  }

  return Renderer({
    topBalance,
    topSymbol,
    bottomBalance,
    bottomSymbol,
    mutation,
    buyAmountInputValue,
    sellAmountInputValue,
    side,
    handleQuoteAmountChange,
    handleBaseAmountChange,
    sellAssetsNeeded,
    handleTopSymbolChange,
    handleBottomSymbolChange,
    handleChangeSide,
    isLimitOrder,
    handleMarketOrderFlagChange,
    limitPriceInputValue,
    limitPrice,
    handlePriceChange,
    setPriceFromMarketPrice,
    noPriceFound,
    canSubmit,
    getMarketPrice: getSimulatedPrice,
    quoteDecimals: market.quoteDecimalPlaces
  })
}