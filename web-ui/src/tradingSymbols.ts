import TradingSymbol from 'tradingSymbol'
import { ConfigurationApiResponse } from 'apiClient'

export default class TradingSymbols {
  native: TradingSymbol[]
  erc20: TradingSymbol[]
  private symbolByName: Map<string, TradingSymbol>

  static fromConfig(config: ConfigurationApiResponse): TradingSymbols {
    return new TradingSymbols(
      config.chains
        .filter((chain) => chain.networkType === 'Evm')
        .map((chain) =>
          chain.symbols.map(
            (symbol) =>
              new TradingSymbol(
                symbol.name,
                chain.name,
                symbol.description,
                symbol.contractAddress,
                symbol.decimals,
                chain.id,
                symbol.faucetSupported,
                symbol.withdrawalFee,
                symbol.iconUrl
              )
          )
        )
        .reduce((accumulator, value) => accumulator.concat(value), [])
    )
  }

  constructor(symbols: TradingSymbol[]) {
    this.native = symbols.filter((s) => s.contractAddress === null)!
    this.erc20 = symbols.filter((s) => s.contractAddress !== null)
    this.symbolByName = new Map(symbols.map((s) => [s.name, s]))
  }

  getByName(name: string): TradingSymbol {
    return this.findByName(name)!
  }

  findByName(name: string): TradingSymbol | undefined {
    return this.symbolByName.get(name)
  }
}
