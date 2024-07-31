import z from 'zod'
import { Zodios } from '@zodios/core'
import { pluginToken } from '@zodios/plugins'
import { loadAuthToken } from 'auth'
import Decimal from 'decimal.js'
import { useEffect, useState } from 'react'

export const apiBaseUrl = import.meta.env.ENV_API_URL

const decimal = () =>
  z
    .instanceof(Decimal)
    .or(z.string())
    .or(z.number())
    .transform((value, ctx) => {
      try {
        return new Decimal(value)
      } catch (error) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: `${value} can't be parsed into Decimal`
        })
        return z.NEVER
      }
    })

export const AddressSchema = z.custom<`0x${string}`>((val: unknown) =>
  /^0x/.test(val as string)
)
export type AddressType = z.infer<typeof AddressSchema>

const DeployedContractSchema = z.object({
  name: z.string(),
  address: AddressSchema
})

export type DeployedContract = z.infer<typeof DeployedContractSchema>

const SymbolSchema = z.object({
  name: z.string(),
  description: z.string(),
  contractAddress: AddressSchema.nullable(),
  decimals: z.number(),
  faucetSupported: z.boolean(),
  iconUrl: z.string(),
  withdrawalFee: z.coerce.bigint()
})

export type SymbolType = z.infer<typeof SymbolSchema>

const AdminSymbolSchema = z.object({
  chainId: z.number(),
  name: z.string(),
  description: z.string(),
  contractAddress: AddressSchema.nullable(),
  decimals: z.number(),
  iconUrl: z.string(),
  withdrawalFee: z.coerce.bigint(),
  addToWallets: z.boolean()
})

export type AdminSymbol = z.infer<typeof AdminSymbolSchema>

const AdminMarketSchema = z.object({
  id: z.string(),
  tickSize: decimal(),
  lastPrice: decimal(),
  minFee: z.coerce.bigint()
})

export type AdminMarket = z.infer<typeof AdminMarketSchema>

const NetworkTypeSchema = z.enum(['Evm', 'Bitcoin'])
export type NetworkType = z.infer<typeof NetworkTypeSchema>

const ChainSchema = z.object({
  id: z.number(),
  name: z.string(),
  contracts: z.array(DeployedContractSchema),
  symbols: z.array(SymbolSchema),
  jsonRpcUrl: z.string(),
  blockExplorerNetName: z.string(),
  blockExplorerUrl: z.string(),
  networkType: NetworkTypeSchema
})
export type Chain = z.infer<typeof ChainSchema>

const MarketSchema = z.object({
  id: z.string(),
  baseSymbol: z.string(),
  quoteSymbol: z.string(),
  tickSize: decimal(),
  lastPrice: decimal(),
  minFee: z.coerce.bigint()
})
export type Market = z.infer<typeof MarketSchema>

const FeeRatesSchema = z.object({
  maker: z.coerce.bigint(),
  taker: z.coerce.bigint()
})
export type FeeRates = z.infer<typeof FeeRatesSchema>

export const ConfigurationApiResponseSchema = z.object({
  chains: z.array(ChainSchema),
  markets: z.array(MarketSchema),
  feeRates: FeeRatesSchema
})
export type ConfigurationApiResponse = z.infer<
  typeof ConfigurationApiResponseSchema
>

export const AccountConfigurationApiResponseSchema = z.object({
  newSymbols: z.array(SymbolSchema),
  role: z.enum(['User', 'Admin'])
})
export type AccountConfigurationApiResponse = z.infer<
  typeof AccountConfigurationApiResponseSchema
>

const OrderSideSchema = z.enum(['Buy', 'Sell'])
export type OrderSide = z.infer<typeof OrderSideSchema>

const FixedAmountSchema = z.object({
  type: z.literal('fixed'),
  value: z.coerce.bigint()
})

const PercentAmountSchema = z.object({
  type: z.literal('percent'),
  value: z.number()
})

const OrderAmountSchema = z.discriminatedUnion('type', [
  FixedAmountSchema,
  PercentAmountSchema
])

const CreateMarketOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('market'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: OrderAmountSchema,
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateMarketOrder = z.infer<typeof CreateMarketOrderSchema>

const CreateBackToBackMarketOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('backToBackMarket'),
  marketId: z.string(),
  secondMarketId: z.string(),
  side: OrderSideSchema,
  amount: OrderAmountSchema,
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateBackToBackMarketOrder = z.infer<
  typeof CreateBackToBackMarketOrderSchema
>

const CreateLimitOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('limit'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: OrderAmountSchema,
  price: decimal(),
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CreateLimitOrder = z.infer<typeof CreateLimitOrderSchema>

const CreateOrderRequestSchema = z.discriminatedUnion('type', [
  CreateMarketOrderSchema,
  CreateLimitOrderSchema,
  CreateBackToBackMarketOrderSchema
])
export type CreateOrderRequest = z.infer<typeof CreateOrderRequestSchema>

const RequestStatusSchema = z.enum(['Accepted', 'Rejected'])
const CreateOrderApiResponseSchema = z.object({
  orderId: z.string(),
  requestStatus: RequestStatusSchema
})

const CancelOrderRequestSchema = z.object({
  orderId: z.string(),
  amount: z.coerce.bigint(),
  marketId: z.string(),
  side: OrderSideSchema,
  nonce: z.string(),
  signature: z.string(),
  verifyingChainId: z.number()
})
export type CancelOrderRequest = z.infer<typeof CancelOrderRequestSchema>

const ExecutionRoleSchema = z.enum(['Maker', 'Taker'])
export type ExecutionRole = z.infer<typeof ExecutionRoleSchema>

const OrderExecutionSchema = z.object({
  timestamp: z.coerce.date(),
  amount: z.coerce.bigint(),
  price: decimal(),
  role: ExecutionRoleSchema,
  feeAmount: z.coerce.bigint(),
  feeSymbol: z.string(),
  marketId: z.string()
})
export type OrderExecution = z.infer<typeof OrderExecutionSchema>

const OrderTimingSchema = z.object({
  createdAt: z.coerce.date(),
  updatedAt: z.coerce.date().nullable(),
  closedAt: z.coerce.date().nullable(),
  sequencerTimeNs: z.coerce.bigint()
})
export type OrderTiming = z.infer<typeof OrderTimingSchema>

const OrderStatusSchema = z.enum([
  'Open',
  'Partial',
  'Filled',
  'Cancelled',
  'Expired',
  'Failed',
  'Rejected'
])
export type OrderStatus = z.infer<typeof OrderStatusSchema>

const MarketOrderSchema = z.object({
  id: z.string(),
  type: z.literal('market'),
  status: OrderStatusSchema,
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  originalAmount: z.coerce.bigint(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type MarketOrder = z.infer<typeof MarketOrderSchema>

const LimitOrderSchema = z.object({
  id: z.string(),
  type: z.literal('limit'),
  status: OrderStatusSchema,
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  price: decimal(),
  originalAmount: z.coerce.bigint(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type LimitOrder = z.infer<typeof LimitOrderSchema>

const BackToBackMarketOrderSchema = z.object({
  id: z.string(),
  type: z.literal('backToBackMarket'),
  status: OrderStatusSchema,
  marketId: z.string(),
  secondMarketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  originalAmount: z.coerce.bigint(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type BackToBackMarketOrder = z.infer<typeof BackToBackMarketOrderSchema>

export const OrderSchema = z
  .discriminatedUnion('type', [
    MarketOrderSchema,
    LimitOrderSchema,
    BackToBackMarketOrderSchema
  ])
  .transform((data) => {
    return {
      ...data,
      isFinal: function (): boolean {
        return (
          ['Filled', 'Cancelled', 'Expired', 'Failed', 'Rejected'].includes(
            data.status
          ) ||
          (data.status == 'Partial' && data.type == 'market')
        )
      }
    }
  })
export type Order = z.infer<typeof OrderSchema>

const TradeSettlementStatusSchema = z.enum([
  'Pending',
  'Settling',
  'FailedSettling',
  'Completed',
  'Failed'
])
export type TradeSettlementStatus = z.infer<typeof TradeSettlementStatusSchema>

export const TradeSchema = z.object({
  id: z.string(),
  timestamp: z.coerce.date(),
  orderId: z.string(),
  executionRole: ExecutionRoleSchema,
  counterOrderId: z.string(),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.coerce.bigint(),
  price: decimal(),
  feeAmount: z.coerce.bigint(),
  feeSymbol: z.string(),
  settlementStatus: TradeSettlementStatusSchema
})
export type Trade = z.infer<typeof TradeSchema>

export const BalanceSchema = z.object({
  symbol: z.string(),
  total: z.coerce.bigint(),
  available: z.coerce.bigint()
})
export type Balance = z.infer<typeof BalanceSchema>

const CreateDepositApiRequestSchema = z.object({
  symbol: z.string(),
  amount: z.coerce.bigint(),
  txHash: z.string()
})

const DepositStatusSchema = z.enum(['Pending', 'Complete', 'Failed'])
export type DepositStatus = z.infer<typeof DepositStatusSchema>

const DepositSchema = z.object({
  id: z.string(),
  symbol: z.string(),
  txHash: z.string(),
  amount: z.coerce.bigint(),
  status: DepositStatusSchema,
  error: z.string().nullable(),
  createdAt: z.coerce.date()
})
export type Deposit = z.infer<typeof DepositSchema>

const DepositApiResponseSchema = z.object({
  deposit: DepositSchema
})

const ListDepositsApiResponseSchema = z.object({
  deposits: z.array(DepositSchema)
})

const CreateWithdrawalApiRequestSchema = z.object({
  symbol: z.string(),
  amount: z.coerce.bigint(),
  nonce: z.number(),
  signature: z.string()
})

const WithdrawalStatusSchema = z.enum([
  'Pending',
  'Sequenced',
  'Settling',
  'Complete',
  'Failed'
])
export type WithdrawalStatus = z.infer<typeof WithdrawalStatusSchema>
const WithdrawalSchema = z.object({
  id: z.string(),
  symbol: z.string(),
  txHash: z.string().nullable(),
  amount: z.coerce.bigint(),
  status: WithdrawalStatusSchema,
  error: z.string().nullable(),
  createdAt: z.coerce.date(),
  fee: z.coerce.bigint()
})
export type Withdrawal = z.infer<typeof WithdrawalSchema>

const WithdrawalApiResponseSchema = z.object({
  withdrawal: WithdrawalSchema
})

const ListWithdrawalsApiResponseSchema = z.object({
  withdrawals: z.array(WithdrawalSchema)
})

const FaucetRequestSchema = z.object({
  symbol: z.string(),
  address: AddressSchema
})
export type FaucetRequest = z.infer<typeof FaucetRequestSchema>

const ApiErrorSchema = z.object({
  displayMessage: z.string()
})
export type ApiError = z.infer<typeof ApiErrorSchema>

const ApiErrorsSchema = z.object({
  errors: z.array(ApiErrorSchema)
})
export type ApiErrors = z.infer<typeof ApiErrorsSchema>

export const apiClient = new Zodios(apiBaseUrl, [
  {
    method: 'get',
    path: '/v1/config',
    alias: 'getConfiguration',
    response: ConfigurationApiResponseSchema
  },
  {
    method: 'get',
    path: '/v1/account-config',
    alias: 'getAccountConfiguration',
    response: AccountConfigurationApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/account-config/:symbolName',
    alias: 'markSymbolAsAdded',
    parameters: [
      {
        name: 'symbolName',
        type: 'Path',
        schema: z.string()
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/orders',
    alias: 'createOrder',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateOrderRequestSchema
      }
    ],
    response: CreateOrderApiResponseSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'delete',
    path: '/v1/orders/:id',
    alias: 'cancelOrder',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CancelOrderRequestSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/deposits',
    alias: 'createDeposit',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateDepositApiRequestSchema
      }
    ],
    response: DepositApiResponseSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/deposits',
    alias: 'listDeposits',
    response: ListDepositsApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/withdrawals',
    alias: 'createWithdrawal',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: CreateWithdrawalApiRequestSchema
      }
    ],
    response: WithdrawalApiResponseSchema,
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/withdrawals',
    alias: 'listWithdrawals',
    response: ListWithdrawalsApiResponseSchema
  },
  {
    method: 'post',
    path: '/v1/faucet',
    alias: 'faucet',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: FaucetRequestSchema
      }
    ],
    response: z.any(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/admin/fee-rates',
    alias: 'setFeeRates',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: FeeRatesSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/admin/admin',
    alias: 'listAdmins',
    parameters: [],
    response: z.array(AddressSchema),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'put',
    path: '/v1/admin/admin/:address',
    alias: 'addAdmin',
    parameters: [
      {
        name: 'address',
        type: 'Path',
        schema: AddressSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'delete',
    path: '/v1/admin/admin/:address',
    alias: 'removeAdmin',
    parameters: [
      {
        name: 'address',
        type: 'Path',
        schema: AddressSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/admin/symbol',
    alias: 'listSymbols',
    parameters: [],
    response: z.array(AdminSymbolSchema),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/admin/symbol',
    alias: 'addSymbol',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminSymbolSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'patch',
    path: '/v1/admin/symbol/:symbol',
    alias: 'patchSymbol',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminSymbolSchema
      },
      {
        name: 'symbol',
        type: 'Path',
        schema: z.string()
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'get',
    path: '/v1/admin/market',
    alias: 'listMarkets',
    parameters: [],
    response: z.array(AdminMarketSchema),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'post',
    path: '/v1/admin/market',
    alias: 'addMarket',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminMarketSchema
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  },
  {
    method: 'patch',
    path: '/v1/admin/market/:base/:quote',
    alias: 'patchMarket',
    parameters: [
      {
        name: 'payload',
        type: 'Body',
        schema: AdminMarketSchema
      },
      {
        name: 'base',
        type: 'Path',
        schema: z.string()
      },
      {
        name: 'quote',
        type: 'Path',
        schema: z.string()
      }
    ],
    response: z.undefined(),
    errors: [
      {
        status: 'default',
        schema: ApiErrorsSchema
      }
    ]
  }
])

apiClient.use(
  pluginToken({
    getToken: async () => {
      return loadAuthToken()
    },
    renewToken: async () => {
      return loadAuthToken({ forceRefresh: true })
    }
  })
)

export function useMaintenance() {
  const [maintenance, setMaintenance] = useState(false)

  useEffect(() => {
    const id = apiClient.axios.interceptors.response.use(
      (response) => {
        setMaintenance(false)
        return response
      },
      (error) => {
        if (error.response.status == 418) {
          setMaintenance(true)
        } else if (maintenance) {
          setMaintenance(false)
        }
        return Promise.reject(error)
      }
    )
    return () => {
      apiClient.axios.interceptors.response.eject(id)
    }
  })
  return maintenance
}
