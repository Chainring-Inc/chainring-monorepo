import { apiClient, Order, UpdateOrderRequest } from 'apiClient'
import { useState } from 'react'
import { Widget } from 'components/common/Widget'
import { TrashIcon } from '@heroicons/react/24/outline'
import { formatUnits, parseUnits } from 'viem'
import { format } from 'date-fns'
import { produce } from 'immer'
import { useMutation } from '@tanstack/react-query'
import { isErrorFromAlias } from '@zodios/core'
import { classNames, cleanAndFormatNumberInput } from 'utils'
import { Modal } from 'components/common/Modal'
import AmountInput from 'components/common/AmountInput'
import SubmitButton from 'components/common/SubmitButton'
import Markets from 'markets'
import { useWebsocketSubscription } from 'contexts/websocket'
import { ordersTopic, Publishable } from 'websocketMessages'

export default function Orders({ markets }: { markets: Markets }) {
  const [orders, setOrders] = useState<Order[]>(() => [])
  const [changedOrder, setChangedOrder] = useState<Order | null>(null)
  const [showChangeModal, setShowChangeModal] = useState<boolean>(false)

  useWebsocketSubscription({
    topic: ordersTopic,
    handler: (message: Publishable) => {
      if (message.type === 'Orders') {
        setOrders(message.orders)
      } else if (message.type === 'OrderCreated') {
        setOrders(
          produce((draft) => {
            draft.unshift(message.order)
          })
        )
      } else if (message.type === 'OrderUpdated') {
        setOrders(
          produce((draft) => {
            const updatedOrder = message.order
            const index = draft.findIndex(
              (order) => order.id === updatedOrder.id
            )
            if (index !== -1) draft[index] = updatedOrder
          })
        )
      }
    }
  })

  function cancelOrder(id: string) {
    if (confirm('Are you sure you want to cancel your order?')) {
      cancelOrderMutation.mutate(id)
    }
  }

  const cancelOrderMutation = useMutation({
    mutationFn: (id: string) =>
      apiClient.cancelOrder(undefined, { params: { id } }),
    onError: (error) => {
      alert(
        isErrorFromAlias(apiClient.api, 'cancelOrder', error)
          ? error.response.data.errors[0].displayMessage
          : 'Something went wrong'
      )
    },
    onSettled: () => {
      cancelOrderMutation.reset()
    }
  })

  function openEditModal(order: Order) {
    setChangedOrder(order)
    setShowChangeModal(true)
  }

  return (
    <Widget
      title={'Orders'}
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
                  <th className="min-w-20 pl-4">Status</th>
                  <th className="pl-4"></th>
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
                {orders.map((order) => {
                  const market = markets.getById(order.marketId)

                  return (
                    <tr
                      key={order.id}
                      className={classNames(
                        'duration-200 ease-in-out hover:cursor-default hover:bg-mutedGray',
                        order.status == 'Cancelled' ? 'line-through' : ''
                      )}
                    >
                      <td>
                        {format(order.timing.createdAt, 'MM/dd HH:mm:ss')}
                      </td>
                      <td className="pl-4">{order.side}</td>
                      <td className="pl-4">
                        {formatUnits(order.amount, market.baseSymbol.decimals)}
                      </td>
                      <td className="pl-4">{order.marketId}</td>
                      <td className="pl-4">
                        {order.type == 'limit' ? order.price : 'MKT'}
                      </td>
                      <td className="pl-4">{order.status}</td>
                      <td className="py-1 pl-4">
                        {!order.isFinal() && (
                          <div className="flex items-center gap-2">
                            <button
                              className="rounded-lg bg-darkGray px-2 py-0.5 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-inset focus:ring-mutedGray"
                              onClick={() => openEditModal(order)}
                              disabled={!cancelOrderMutation.isIdle}
                            >
                              Change
                            </button>
                            <button
                              onClick={() => cancelOrder(order.id)}
                              disabled={!cancelOrderMutation.isIdle}
                            >
                              <TrashIcon className="size-4" />
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {changedOrder && (
            <ChangeOrderModal
              isOpen={showChangeModal}
              order={changedOrder}
              markets={markets}
              close={() => setShowChangeModal(false)}
              onClosed={() => setChangedOrder(null)}
            />
          )}
        </>
      }
    />
  )
}

function ChangeOrderModal({
  order,
  markets,
  isOpen,
  close,
  onClosed
}: {
  order: Order
  markets: Markets
  isOpen: boolean
  close: () => void
  onClosed: () => void
}) {
  const market = markets.getById(order.marketId)
  const baseSymbol = market.baseSymbol
  const quoteSymbol = market.quoteSymbol

  const [amount, setAmount] = useState(
    formatUnits(order.amount, baseSymbol.decimals)
  )
  const [price, setPrice] = useState(
    order.type == 'limit' ? String(order.price) : ''
  )

  const mutation = useMutation({
    mutationFn: (payload: UpdateOrderRequest) =>
      apiClient.updateOrder(payload, { params: { id: payload.id } }),
    onSuccess: () => {
      close()
    }
  })

  async function onSubmit() {
    mutation.mutate(
      order.type === 'market'
        ? {
            id: order.id,
            type: 'market',
            amount: parseUnits(amount, baseSymbol.decimals)
          }
        : {
            id: order.id,
            type: 'limit',
            amount: parseUnits(amount, baseSymbol.decimals),
            price: Number(price)
          }
    )
  }

  return (
    <Modal
      isOpen={isOpen}
      close={close}
      onClosed={onClosed}
      title={'Change order'}
    >
      <div className="overflow-y-auto text-sm">
        <div className="flex flex-col gap-2">
          <div>Market: {order.marketId}</div>
          <div>Side: {order.side}</div>
          <div>
            <label className="block">Amount</label>
            <AmountInput
              value={amount}
              symbol={baseSymbol.name}
              disabled={mutation.isPending}
              onChange={(e) =>
                setAmount(
                  cleanAndFormatNumberInput(e.target.value, baseSymbol.decimals)
                )
              }
            />
          </div>

          {order.type != 'market' && (
            <div>
              <label className="block">Price</label>
              <AmountInput
                value={price}
                symbol={quoteSymbol.name}
                disabled={mutation.isPending}
                onChange={(e) =>
                  setPrice(
                    cleanAndFormatNumberInput(
                      e.target.value,
                      quoteSymbol.decimals
                    )
                  )
                }
              />
            </div>
          )}
        </div>

        <SubmitButton
          disabled={
            !(
              amount &&
              (order.type === 'market' || price) &&
              !mutation.isPending
            )
          }
          onClick={onSubmit}
          error={
            mutation.isError
              ? isErrorFromAlias(apiClient.api, 'updateOrder', mutation.error)
                ? mutation.error.response.data.errors[0].displayMessage
                : 'Something went wrong'
              : ''
          }
          caption={() => {
            if (mutation.isPending) {
              return 'Submitting...'
            } else {
              return 'Submit'
            }
          }}
        />
      </div>
    </Modal>
  )
}