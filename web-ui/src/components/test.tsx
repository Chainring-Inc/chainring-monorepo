import { render, screen } from '@testing-library/react'

import App from 'components/App'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { WagmiProvider } from 'wagmi'
import { initializeWagmiConfig, wagmiConfig } from 'wagmiConfig'
import { WebSocket } from 'mock-socket'

describe('<App />', () => {
  global.WebSocket = WebSocket
  it('should render the App', () => {
    initializeWagmiConfig().then(() => {
      const { container } = render(
        <WagmiProvider config={wagmiConfig}>
          <QueryClientProvider client={new QueryClient()}>
            <App />
          </QueryClientProvider>
        </WagmiProvider>
      )

      expect(screen.getAllByAltText('funkybit')[0]).toBeInTheDocument()

      expect(
        screen.getByRole('button', {
          name: 'Connect Wallet'
        })
      ).toBeInTheDocument()

      expect(container.firstChild).toBeInTheDocument()
    })
  })
})
