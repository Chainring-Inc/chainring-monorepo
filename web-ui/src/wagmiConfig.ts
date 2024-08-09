import { Config, createConfig, http } from 'wagmi'
import { coinbaseWallet, injected, walletConnect } from 'wagmi/connectors'
import { createWeb3Modal } from '@web3modal/wagmi/react'
import { Chain, defineChain } from 'viem'
import { apiClient, ConfigurationApiResponseSchema } from 'apiClient'

const walletConnectProjectId = '03908a0893516a0f391370f3a9349b8e'

const walletConnectMetadata = {
  name: 'funkybit',
  description: 'The first cross-chain DEX built on Bitcoin',
  url: '', // origin must match your domain & subdomain
  icons: ['https://avatars.githubusercontent.com/u/37784886'] // TODO CHAIN-423 - update for funkybit
}

export let allChains: [Chain, ...Chain[]]
export let wagmiConfig: Config

export const initializeWagmiConfig = async () => {
  const apiConfig = ConfigurationApiResponseSchema.parse(
    await apiClient.getConfiguration()
  )

  const chains = apiConfig.chains
    .filter((chain) => chain.networkType === 'Evm')
    .map((chain) => {
      const nativeSymbol = chain.symbols.filter(
        (symbol) => symbol.contractAddress == null
      )[0]

      return defineChain({
        id: chain.id,
        name: chain.name,
        nativeCurrency: {
          decimals: nativeSymbol.decimals,
          name: nativeSymbol.description,
          symbol: nativeSymbol.name.replace(new RegExp(':.*', ''), '')
        },
        rpcUrls: {
          default: { http: [chain.jsonRpcUrl] }
        },
        blockExplorers: {
          default: {
            name: chain.blockExplorerNetName,
            url: chain.blockExplorerUrl
          }
        }
      })
    })

  if (isNonEmptyArray(chains)) {
    allChains = chains
  } else {
    throw new Error('No chains available in the configuration')
  }

  wagmiConfig = createConfig({
    chains: allChains,
    transports: chains.reduce(
      (acc: Record<number, ReturnType<typeof http>>, chain: Chain) => {
        acc[chain.id] = http()
        return acc
      },
      {}
    ),
    connectors: [
      walletConnect({
        projectId: walletConnectProjectId,
        metadata: walletConnectMetadata,
        showQrModal: false
      }),
      injected({ shimDisconnect: true }),
      coinbaseWallet({
        appName: walletConnectMetadata.name,
        appLogoUrl: walletConnectMetadata.icons[0]
      })
    ]
  })

  createWeb3Modal({
    wagmiConfig: wagmiConfig,
    projectId: walletConnectProjectId,
    enableAnalytics: true,
    enableOnramp: true
  })
}

function isNonEmptyArray<T>(array: T[]): array is [T, ...T[]] {
  return array.length > 0
}

// Make wagmi config more type safe, see https://wagmi.sh/react/typescript#config-types
declare module 'wagmi' {
  interface Register {
    config: typeof wagmiConfig
  }
}
