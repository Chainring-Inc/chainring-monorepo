import { Publishable, tradesTopic } from 'websocketMessages'
import { useState } from 'react'
import { Widget } from 'components/common/Widget'
import { formatUnits } from 'viem'
import { format } from 'date-fns'
import { Trade } from 'apiClient'
import { useWebsocketSubscription } from 'contexts/websocket'
import Markets from 'markets'
import { produce } from 'immer'

export default function TradeHistory({ markets }: { markets: Markets }) {
  const [trades, setTrades] = useState<Trade[]>(() => [])

  useWebsocketSubscription({
    topic: tradesTopic,
    handler: (message: Publishable) => {
      if (message.type === 'Trades') {
        setTrades(message.trades)
      } else if (message.type === 'TradeCreated') {
        setTrades(
          produce((draft) => {
            draft.unshift(message.trade)
          })
        )
      } else if (message.type === 'TradeUpdated') {
        setTrades(
          produce((draft) => {
            const updatedTrade = message.trade
            const index = draft.findIndex(
              (trade) =>
                trade.id === updatedTrade.id && trade.side === updatedTrade.side
            )
            if (index !== -1) draft[index] = updatedTrade
          })
        )
      }
    }
  })

  return (
    <Widget
      title={'Trade History'}
      contents={
        <>
          <div className="h-96 overflow-scroll">
            <table className="relative w-full text-left text-sm">
              <thead className="sticky top-0 bg-black">
                <tr key="header">
                  <th className="min-w-32">Date</th>
                  <th className="min-w-16 pl-4">Side</th>
                  <th className="min-w-20 pl-4">Amount</th>
                  <th className="min-w-20 pl-4">Market</th>
                  <th className="min-w-20 pl-4">Price</th>
                  <th className="min-w-20 pl-4">Fee</th>
                  <th className="min-w-20 pl-4">Settlement</th>
                </tr>
                <tr key="header-divider">
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                  <th className="h-px bg-lightBackground p-0"></th>
                </tr>
              </thead>
              <tbody>
                {trades.map((trade) => {
                  const market = markets.getById(trade.marketId)

                  return (
                    <tr
                      key={`${trade.id}-${trade.side}`}
                      className="duration-200 ease-in-out hover:cursor-default hover:bg-mutedGray"
                    >
                      <td>{format(trade.timestamp, 'MM/dd HH:mm:ss')}</td>
                      <td className="pl-4">{trade.side}</td>
                      <td className="pl-4">{formatUnits(trade.amount, 18)}</td>
                      <td className="pl-4">{trade.marketId}</td>
                      <td className="pl-4">
                        {trade.price.toFixed(market.quoteDecimalPlaces)}
                      </td>
                      <td className="pl-4">
                        {formatUnits(trade.feeAmount, 18)}
                      </td>
                      <td className="pl-4">{trade.settlementStatus}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      }
    />
  )
}
