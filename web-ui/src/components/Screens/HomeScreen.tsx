import { useQuery } from '@tanstack/react-query'
import { apiClient } from 'apiClient'
import { useAccount } from 'wagmi'
import BalancesWidget from 'components/Screens/HomeScreen/balances/BalancesWidget'
import { Header } from 'components/Screens/Header'
import { OrderBookWidget } from 'components/Screens/HomeScreen/OrderBookWidget'
import OrderTicketWidget from 'components/Screens/HomeScreen/OrderTicketWidget'
import { useEffect, useMemo, useState } from 'react'
import Spinner from 'components/common/Spinner'
import OrdersAndTradesWidget from 'components/Screens/HomeScreen/OrdersAndTradesWidget'
import TradingSymbols from 'tradingSymbols'
import Markets, { Market } from 'markets'
import { WebsocketProvider } from 'contexts/websocket'
import { PricesWidgetD3 } from 'components/Screens/HomeScreen/PricesWidgetD3'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: apiClient.getConfiguration
  })

  const wallet = useAccount()

  const [selectedMarket, setSelectedMarket] = useState<Market | null>(null)

  const { exchangeContract, markets, symbols } = useMemo(() => {
    const config = configQuery.data
    const chainConfig = config?.chains.find(
      (chain) => chain.id === (wallet.chainId || config.chains[0]?.id)
    )

    const exchangeContract = chainConfig?.contracts?.find(
      (c) => c.name == 'Exchange'
    )

    const symbols = chainConfig ? new TradingSymbols(chainConfig.symbols) : null
    const markets =
      config && symbols ? new Markets(config.markets, symbols) : null

    return {
      exchangeContract,
      markets,
      symbols
    }
  }, [configQuery.data, wallet.chainId])

  useEffect(() => {
    if (markets !== null && selectedMarket == null) {
      setSelectedMarket(markets.first())
    }
  }, [markets, selectedMarket])

  return (
    <WebsocketProvider wallet={wallet}>
      {markets && selectedMarket ? (
        <div className="bg-darkBluishGray10">
          <Header
            markets={markets}
            selectedMarket={selectedMarket}
            onMarketChange={setSelectedMarket}
          />

          <div className="flex justify-center py-24">
            <div className="w-3/4 min-w-[1200px]">
              <div className="grid grid-cols-3 gap-4">
                <div className="col-span-2 space-y-4">
                  <PricesWidgetD3 market={selectedMarket} />
                  {wallet.address && symbols && exchangeContract && (
                    <BalancesWidget
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract.address}
                      symbols={symbols}
                    />
                  )}
                </div>
                <div className="col-span-1 space-y-4">
                  {wallet.address && exchangeContract && (
                    <OrderTicketWidget
                      market={selectedMarket}
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract.address}
                    />
                  )}
                  <OrderBookWidget marketId={selectedMarket.id} />
                </div>
                <div className="col-span-3  space-y-4">
                  {wallet.address && exchangeContract && (
                    <OrdersAndTradesWidget
                      markets={markets}
                      walletAddress={wallet.address}
                      exchangeContractAddress={exchangeContract.address}
                    />
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>
      ) : (
        <div className="flex h-screen items-center justify-center bg-darkBluishGray10">
          <Spinner />
        </div>
      )}
    </WebsocketProvider>
  )
}
