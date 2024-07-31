import { apiClient, Chain, OrderSide } from 'apiClient'
import { useAccount } from 'wagmi'
import BalancesWidget from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { Header, Tab } from 'components/Screens/Header'
import React, { LegacyRef, useEffect, useMemo, useState } from 'react'
import OrdersAndTradesWidget from 'components/Screens/HomeScreen/OrdersAndTradesWidget/OrdersAndTradesWidget'
import TradingSymbols from 'tradingSymbols'
import Markets, { Market } from 'markets'
import { PricesWidget } from 'components/Screens/HomeScreen/PricesWidget'
import { useMeasure } from 'react-use'
import { SwapModal } from 'components/Screens/HomeScreen/swap/SwapModal'
import { SwapWidget } from 'components/Screens/HomeScreen/swap/SwapWidget'
import { LimitModal } from 'components/Screens/HomeScreen/swap/LimitModal'
import { useQuery } from '@tanstack/react-query'
import Spinner from 'components/common/Spinner'
import { WebsocketProvider } from 'contexts/websocket'
import { OrderBookWidget } from 'components/Screens/HomeScreen/OrderBookWidget'
import Admin from 'components/Screens/Admin'

function WebsocketWrapper({ contents }: { contents: JSX.Element }) {
  const wallet = useAccount()
  return <WebsocketProvider wallet={wallet}>{contents}</WebsocketProvider>
}

function HomeScreenContent() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const wallet = useAccount()

  const [selectedMarket, setSelectedMarket] = useState<Market | null>(null)
  const [side, setSide] = useState<OrderSide>(
    (window.sessionStorage.getItem('side') as OrderSide | null) ?? 'Buy'
  )
  const [tab, setTab] = useState<Tab>(
    (window.sessionStorage.getItem('tab') as Tab | null) ?? 'Swap'
  )

  const {
    exchangeContract,
    chains,
    markets,
    symbols,
    feeRates,
    marketsWithBackToBack
  } = useMemo(() => {
    const config = configQuery.data
    const chainConfig = config?.chains
      .filter((chain) => chain.networkType === 'Evm')
      .find((chain) => chain.id === (wallet.chainId || config.chains[0]?.id))

    const exchangeContract = chainConfig?.contracts?.find(
      (c) => c.name == 'Exchange'
    )

    const symbols = config ? TradingSymbols.fromConfig(config) : null
    const markets =
      config && symbols ? new Markets(config.markets, symbols, false) : null

    const marketsWithBackToBack =
      config && symbols ? new Markets(config.markets, symbols, true) : null

    const feeRates = config && config.feeRates

    const chains: Chain[] = config?.chains ?? []

    return {
      exchangeContract,
      chains,
      markets,
      symbols,
      feeRates,
      marketsWithBackToBack
    }
  }, [configQuery.data, wallet.chainId])

  useEffect(() => {
    if (markets !== null && selectedMarket == null) {
      const savedMarketId = window.sessionStorage.getItem('market')
      if (savedMarketId) {
        const market = markets.findById(savedMarketId)
        if (market) {
          setSelectedMarket(market)
        } else {
          setSelectedMarket(markets.first())
        }
      } else {
        setSelectedMarket(markets.first())
      }
    }
  }, [markets, selectedMarket])

  const [homeScreenRef, { width }] = useMeasure()
  const [pricesRef, { height: pricesMeasuredHeight }] = useMeasure()
  const [balancesRef, { height: balancesMeasuredHeight }] = useMeasure()
  const [swapRef, { height: swapMeasuredHeight }] = useMeasure()

  const defaultSide = useMemo(() => {
    if (selectedMarket) {
      const price = selectedMarket.lastPrice.toNumber()
      if (price > 1.1) {
        return 'Buy'
      } else if (price < 0.9) {
        return 'Sell'
      } else {
        return side
      }
    } else {
      return 'Buy'
    }
  }, [side, selectedMarket])

  const [overriddenSide, setOverriddenSide] = useState<OrderSide | undefined>()
  const [showAdmin, setShowAdmin] = useState(false)

  function saveTab(tab: Tab) {
    setTab(tab)
    window.sessionStorage.setItem('tab', tab)
  }

  return markets && marketsWithBackToBack && feeRates && selectedMarket ? (
    showAdmin ? (
      <Admin onClose={() => setShowAdmin(false)} />
    ) : (
      <div className="min-h-screen bg-darkBluishGray10">
        <Header
          initialTab={tab}
          markets={markets}
          onTabChange={saveTab}
          onShowAdmin={() => setShowAdmin(true)}
        />

        <div className="mx-4 flex min-h-screen justify-center py-24">
          <div
            className="my-auto laptop:max-w-[1800px]"
            ref={homeScreenRef as LegacyRef<HTMLDivElement>}
          >
            {tab === 'Swap' && (
              <SwapModal
                markets={marketsWithBackToBack}
                walletAddress={wallet.address}
                exchangeContractAddress={exchangeContract?.address}
                feeRates={feeRates}
                onMarketChange={setSelectedMarket}
                onSideChange={setSide}
              />
            )}
            {tab === 'Limit' && (
              <LimitModal
                markets={markets}
                walletAddress={wallet.address}
                exchangeContractAddress={exchangeContract?.address}
                feeRates={feeRates}
                onMarketChange={setSelectedMarket}
                onSideChange={setSide}
              />
            )}
            {tab === 'Dashboard' && (
              <div className="grid grid-cols-1 gap-4 laptop:grid-cols-3">
                <div className="col-span-1 space-y-4 laptop:col-span-2">
                  <div ref={pricesRef as LegacyRef<HTMLDivElement>}>
                    <PricesWidget
                      side={defaultSide}
                      market={selectedMarket}
                      onSideChanged={setOverriddenSide}
                    />
                  </div>
                  {symbols && width >= 1100 && (
                    <div ref={balancesRef as LegacyRef<HTMLDivElement>}>
                      <BalancesWidget
                        walletAddress={wallet.address}
                        exchangeContractAddress={exchangeContract?.address}
                        symbols={symbols}
                        chains={chains}
                      />
                    </div>
                  )}
                </div>
                <div className="col-span-1 space-y-4">
                  <div ref={swapRef as LegacyRef<HTMLDivElement>}>
                    <SwapWidget
                      markets={markets}
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract?.address}
                      feeRates={feeRates}
                      onMarketChange={setSelectedMarket}
                      onSideChange={setSide}
                    />
                  </div>
                  {width >= 1100 && (
                    <OrderBookWidget
                      market={selectedMarket}
                      side={overriddenSide ?? defaultSide}
                      height={
                        pricesMeasuredHeight +
                        balancesMeasuredHeight -
                        swapMeasuredHeight
                      }
                    />
                  )}
                </div>
                {symbols && width < 1100 && (
                  <div className="col-span-1 space-y-4">
                    <OrderBookWidget
                      market={selectedMarket}
                      side={overriddenSide ?? defaultSide}
                      height={500}
                    />
                    <BalancesWidget
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract?.address}
                      symbols={symbols}
                      chains={chains}
                    />
                  </div>
                )}
                <div className="col-span-1 space-y-4 laptop:col-span-3">
                  <OrdersAndTradesWidget
                    markets={markets}
                    walletAddress={wallet.address}
                    exchangeContractAddress={exchangeContract?.address}
                  />
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    )
  ) : (
    <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
      <Spinner />
    </div>
  )
}

export default function HomeScreen() {
  return <WebsocketWrapper contents={<HomeScreenContent />} />
}
