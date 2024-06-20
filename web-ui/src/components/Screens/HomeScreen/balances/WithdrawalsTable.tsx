import { Chain, Withdrawal } from 'apiClient'
import TradingSymbols from 'tradingSymbols'
import { format } from 'date-fns'
import { formatUnits } from 'viem'
import React, { Fragment } from 'react'
import { Status } from 'components/common/Status'
import { SymbolAndChain } from 'components/common/SymbolAndChain'
import { ExpandableValue } from 'components/common/ExpandableValue'
import { TxHashDisplay } from 'components/common/TxHashDisplay'

export function WithdrawalsTable({
  withdrawals,
  symbols,
  chains
}: {
  withdrawals: Withdrawal[]
  symbols: TradingSymbols
  chains: Chain[]
}) {
  return (
    <div className="grid max-h-72 auto-rows-max grid-cols-[max-content_max-content_1fr_max-content_max-content] items-center overflow-scroll">
      {withdrawals.map((withdrawal) => {
        const symbol = symbols.getByName(withdrawal.symbol)

        return (
          <Fragment key={withdrawal.id}>
            <div className="mb-4 ml-4 mr-8 inline-block align-text-top text-sm">
              <span className="mr-2 text-lightBluishGray5">
                {format(withdrawal.createdAt, 'MM/dd')}
              </span>
              <span className="text-white">
                {format(withdrawal.createdAt, 'HH:mm:ss a')}
              </span>
            </div>
            <div className="mb-4 mr-4 inline-block whitespace-nowrap align-text-top text-sm">
              <SymbolAndChain symbol={symbol} />
            </div>
            <div className="mb-4 inline-block w-full text-center align-text-top text-sm">
              <ExpandableValue
                value={formatUnits(withdrawal.amount, symbol.decimals)}
              />
            </div>
            <div className="mb-4 mr-4 inline-block text-center align-text-top text-sm">
              {withdrawal.txHash && (
                <TxHashDisplay
                  txHash={withdrawal.txHash}
                  blockExplorerUrl={
                    chains.find((chain) => chain.id == symbol.chainId)
                      ?.blockExplorerUrl
                  }
                />
              )}
            </div>
            <div className="mb-4 mr-4 inline-block text-center align-text-top text-sm">
              <Status status={withdrawal.status} />
            </div>
          </Fragment>
        )
      })}
      {withdrawals.length === 0 && (
        <div className="col-span-4 w-full text-center">No withdrawals yet</div>
      )}
    </div>
  )
}
