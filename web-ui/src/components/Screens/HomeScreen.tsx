import { useQuery } from '@tanstack/react-query'
import { getConfiguration } from 'ApiClient'
import { useAccount } from 'wagmi'
import Balances from 'components/Screens/HomeScreen/Balances'

export default function HomeScreen() {
  const configQuery = useQuery({
    queryKey: ['configuration'],
    queryFn: getConfiguration
  })

  const wallet = useAccount()
  const walletAddress = wallet.address

  const exchangeContractAddress = (configQuery.data?.contracts || []).find(
    (c) => c.name == 'Exchange'
  )?.address

  return (
    <div className="flex h-screen items-center justify-center bg-red-900 py-48">
      <div className="flex flex-col items-center gap-4">
        {walletAddress && exchangeContractAddress && (
          <>
            <Balances
              exchangeContractAddress={exchangeContractAddress}
              walletAddress={walletAddress}
              erc20TokenContracts={configQuery.data?.erc20Tokens || []}
            />
          </>
        )}
      </div>
    </div>
  )
}
