import { render, screen } from '@testing-library/react'

import App from './App'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { WagmiProvider } from 'wagmi'
import { wagmiConfig } from '../wagmiConfig'

describe('<App />', () => {
  it('should render the App', () => {
    const { container } = render(
      <WagmiProvider config={wagmiConfig}>
        <QueryClientProvider client={new QueryClient()}>
          <App />
        </QueryClientProvider>
      </WagmiProvider>
    )

    expect(screen.getAllByAltText('ChainRing')[0]).toBeInTheDocument()

    expect(
      screen.getByRole('button', {
        name: 'Connect Wallet'
      })
    ).toBeInTheDocument()

    expect(container.firstChild).toBeInTheDocument()
  })
})
