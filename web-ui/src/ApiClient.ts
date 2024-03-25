import z from 'zod'
import { Zodios } from '@zodios/core'

export const apiBaseUrl = import.meta.env.ENV_API_URL

const AddressSchema = z.custom<`0x${string}`>((val: unknown) =>
  /^0x/.test(val as string)
)

const DeployedContractSchema = z.object({
  name: z.string(),
  address: AddressSchema
})

export type DeployedContract = z.infer<typeof DeployedContractSchema>

const SymbolSchema = z.object({
  name: z.string(),
  description: z.string(),
  contractAddress: AddressSchema.nullable(),
  decimals: z.number()
})

export type TradingSymbol = z.infer<typeof SymbolSchema>

const ChainSchema = z.object({
  id: z.number(),
  contracts: z.array(DeployedContractSchema),
  symbols: z.array(SymbolSchema)
})
export type Chain = z.infer<typeof ChainSchema>

const MarketSchema = z.object({
  id: z.string(),
  baseSymbol: z.string(),
  quoteSymbol: z.string()
})
export type Market = z.infer<typeof MarketSchema>

const ConfigurationApiResponseSchema = z.object({
  chains: z.array(ChainSchema),
  markets: z.array(MarketSchema)
})
export type ConfigurationApiResponse = z.infer<
  typeof ConfigurationApiResponseSchema
>

const OrderSideSchema = z.enum(['Buy', 'Sell'])
export type OrderSide = z.infer<typeof OrderSideSchema>

const CreateMarketOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('market'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.number()
})
export type CreateMarketOrder = z.infer<typeof CreateMarketOrderSchema>

const CreateLimitOrderSchema = z.object({
  nonce: z.string(),
  type: z.literal('limit'),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.number(),
  price: z.number()
})
export type CreateLimitOrder = z.infer<typeof CreateLimitOrderSchema>

const CreateOrderRequestSchema = z.discriminatedUnion('type', [
  CreateMarketOrderSchema,
  CreateLimitOrderSchema
])
export type CreateOrderRequest = z.infer<typeof CreateOrderRequestSchema>

const ExecutionRoleSchema = z.enum(['Maker', 'Taker'])
export type ExecutionRole = z.infer<typeof ExecutionRoleSchema>

const OrderExecutionSchema = z.object({
  timestamp: z.coerce.date(),
  amount: z.number(),
  price: z.number(),
  role: ExecutionRoleSchema,
  feeAmount: z.number(),
  feeSymbol: z.string()
})
export type OrderExecution = z.infer<typeof OrderExecutionSchema>

const OrderTimingSchema = z.object({
  createdAt: z.coerce.date(),
  updatedAt: z.coerce.date().nullable(),
  filledAt: z.coerce.date().nullable(),
  closedAt: z.coerce.date().nullable(),
  expiredAt: z.coerce.date().nullable()
})
export type OrderTiming = z.infer<typeof OrderTimingSchema>

const MarketOrderSchema = z.object({
  id: z.string(),
  type: z.literal('market'),
  status: z.string(),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.number(),
  originalAmount: z.number(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type MarketOrder = z.infer<typeof MarketOrderSchema>

const LimitOrderSchema = z.object({
  id: z.string(),
  type: z.literal('limit'),
  status: z.string(),
  marketId: z.string(),
  side: OrderSideSchema,
  amount: z.number(),
  price: z.number(),
  originalAmount: z.number(),
  executions: z.array(OrderExecutionSchema),
  timing: OrderTimingSchema
})
export type LimitOrder = z.infer<typeof LimitOrderSchema>

const OrderSchema = z.discriminatedUnion('type', [
  MarketOrderSchema,
  LimitOrderSchema
])

const OrderBookEntrySchema = z.object({
  price: z.string(),
  size: z.number()
})
export type OrderBookEntry = z.infer<typeof OrderBookEntrySchema>

const DirectionSchema = z.enum(['Up', 'Down'])
export type Direction = z.infer<typeof DirectionSchema>

const LastTradeSchema = z.object({
  price: z.string(),
  direction: DirectionSchema
})
export type LastTrade = z.infer<typeof LastTradeSchema>

const IncomingWSMessageTypeSchema = z.enum(['OrderBook', 'Prices'])
export type IncomingWSMessageType = z.infer<typeof IncomingWSMessageTypeSchema>

export const OrderBookSchema = z.object({
  type: z.literal('OrderBook'),
  buy: z.array(OrderBookEntrySchema),
  sell: z.array(OrderBookEntrySchema),
  last: LastTradeSchema
})
export type OrderBook = z.infer<typeof OrderBookSchema>

const OHLCSchema = z.object({
  start: z.coerce.date(),
  durationMs: z.number(),
  open: z.number(),
  high: z.number(),
  low: z.number(),
  close: z.number(),
  incomplete: z.boolean().optional()
})
export type OHLC = z.infer<typeof OHLCSchema>

export const PricesSchema = z.object({
  type: z.literal('Prices'),
  full: z.boolean(),
  ohlc: z.array(OHLCSchema)
})
export type Prices = z.infer<typeof PricesSchema>

export type Publish = {
  type: 'Publish'
  data: Publishable
}

const PublishableSchema = z.discriminatedUnion('type', [
  OrderBookSchema,
  PricesSchema
])
export type Publishable = z.infer<typeof PublishableSchema>

export type IncomingWSMessage = Publish

export const apiClient = new Zodios(apiBaseUrl, [
  {
    method: 'get',
    path: '/v1/config',
    alias: 'getConfiguration',
    response: ConfigurationApiResponseSchema
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
    response: OrderSchema
  }
])
