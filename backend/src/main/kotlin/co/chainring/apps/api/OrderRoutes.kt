package co.chainring.apps.api

import co.chainring.apps.api.middleware.principal
import co.chainring.apps.api.middleware.signedTokenSecurity
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.ReasonCode
import co.chainring.apps.api.model.TradesApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.badRequestError
import co.chainring.apps.api.model.marketNotSupportedError
import co.chainring.apps.api.model.orderIsClosedError
import co.chainring.apps.api.model.orderNotFoundError
import co.chainring.apps.api.model.websocket.OrderUpdated
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ContractType
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.model.Address
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.OrderEntity
import co.chainring.core.model.db.OrderExecutionEntity
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.WalletEntity
import co.chainring.core.services.ExchangeService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.http4k.contract.ContractRoute
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.string
import org.jetbrains.exposed.sql.transactions.transaction

class OrderRoutes(private val exchangeService: ExchangeService) {
    private val logger = KotlinLogging.logger {}

    private val orderIdPathParam = Path.map(::OrderId, OrderId::value).of("orderOd", "Order Id")

    fun createOrder(): ContractRoute {
        val requestBody = Body.auto<CreateOrderApiRequest>().toLens()
        val responseBody = Body.auto<Order>().toLens()

        return "orders" meta {
            operationId = "create-order"
            summary = "Create order"
            security = signedTokenSecurity
            receiving(
                requestBody to Examples.createMarketOrderRequest,
            )
            receiving(
                requestBody to Examples.createLimitOrderRequest,
            )
            returning(
                Status.CREATED,
                responseBody to Examples.marketOrderResponse,
            )
            returning(
                Status.CREATED,
                responseBody to Examples.limitOrderResponse,
            )
        } bindContract Method.POST to { request ->
            val apiRequest: CreateOrderApiRequest = requestBody(request)

            val (wallet, market, isSignatureValid) = transaction {
                val market = MarketEntity.findById(apiRequest.marketId)
                Triple(
                    WalletEntity.getOrCreate(request.principal),
                    market,
                    validateSignature(market, request.principal, apiRequest, exchangeService.blockchainClient),
                )
            }
            when {
                market == null -> marketNotSupportedError

                !isSignatureValid -> badRequestError(ReasonCode.SignatureNotValid, "Signature not verified")

                else -> {
                    ApiUtils.runCatchingValidation {
                        val order = exchangeService.addOrder(wallet, market, apiRequest)
                        Response(Status.CREATED).with(
                            responseBody of order,
                        )
                    }
                }
            }
        }
    }

    fun updateOrder(): ContractRoute {
        val requestBody = Body.auto<UpdateOrderApiRequest>().toLens()
        val responseBody = Body.auto<Order>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "update-order"
            summary = "Update order"
            security = signedTokenSecurity
            receiving(
                requestBody to Examples.updateMarketOrderRequest,
            )
            receiving(
                requestBody to Examples.updateLimitOrderRequest,
            )
            returning(
                Status.OK,
                responseBody to Examples.marketOrderResponse,
            )
            returning(
                Status.OK,
                responseBody to Examples.limitOrderResponse,
            )
        } bindContract Method.PATCH to { orderId ->
            fun handle(request: Request): Response {
                val apiRequest: UpdateOrderApiRequest = requestBody(request)
                return transaction {
                    val orderEntity = OrderEntity.findById(orderId)
                    when {
                        orderEntity == null -> orderNotFoundError
                        orderEntity.status.isFinal() -> orderIsClosedError

                        else -> {
                            orderEntity.update(
                                amount = apiRequest.amount,
                                price = when (apiRequest) {
                                    is UpdateOrderApiRequest.Market -> null
                                    is UpdateOrderApiRequest.Limit -> apiRequest.price
                                },
                            )
                            orderEntity.refresh(flush = true)

                            val order = orderEntity.toOrderResponse().also {
                                exchangeService.broadcaster.notify(request.principal, OrderUpdated(it))
                            }

                            Response(Status.OK).with(
                                responseBody of order,
                            )
                        }
                    }
                }
            }
            ::handle
        }
    }

    fun cancelOrder(): ContractRoute {
        return "orders" / orderIdPathParam meta {
            operationId = "cancel-order"
            summary = "Cancel order"
            security = signedTokenSecurity
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.DELETE to { orderId ->
            { request: Request ->
                transaction {
                    val order = OrderEntity.findById(orderId)
                    when {
                        order == null -> orderNotFoundError
                        order.status == OrderStatus.Cancelled -> Response(Status.NO_CONTENT)
                        order.status.isFinal() -> orderIsClosedError

                        else -> {
                            ApiUtils.runCatchingValidation {
                                exchangeService.cancelOrder(order)
                                Response(Status.NO_CONTENT)
                            }
                        }
                    }
                }
            }
        }
    }

    fun getOrder(): ContractRoute {
        val responseBody = Body.auto<Order>().toLens()

        return "orders" / orderIdPathParam meta {
            operationId = "get-order"
            summary = "Get order"
            security = signedTokenSecurity
            returning(
                Status.OK,
                responseBody to Examples.marketOrderResponse,
            )
            returning(
                Status.OK,
                responseBody to Examples.limitOrderResponse,
            )
        } bindContract Method.GET to { orderId ->
            { _: Request ->
                transaction {
                    when (val order = OrderEntity.findById(orderId)) {
                        null -> orderNotFoundError
                        else -> Response(Status.OK).with(
                            responseBody of order.toOrderResponse(),
                        )
                    }
                }
            }
        }
    }

    fun listOrders(): ContractRoute {
        val responseBody = Body.auto<OrdersApiResponse>().toLens()

        return "orders" meta {
            operationId = "list-orders"
            summary = "List orders"
            security = signedTokenSecurity
            returning(
                Status.OK,
                responseBody to OrdersApiResponse(
                    orders = listOf(Examples.marketOrderResponse, Examples.limitOrderResponse),
                ),
            )
        } bindContract Method.GET to { request: Request ->
            val orders = transaction {
                OrderEntity.listOrders(WalletEntity.getOrCreate(request.principal)).map { it.toOrderResponse() }
            }
            Response(Status.OK).with(
                responseBody of OrdersApiResponse(
                    orders = orders,
                ),
            )
        }
    }

    fun cancelOpenOrders(): ContractRoute {
        return "orders" meta {
            operationId = "cancel-open-orders"
            summary = "Cancel open orders"
            security = signedTokenSecurity
        } bindContract Method.DELETE to { request ->
            ApiUtils.runCatchingValidation {
                exchangeService.cancelOpenOrders(
                    transaction { WalletEntity.getOrCreate(request.principal) },
                )
                Response(Status.NO_CONTENT)
            }
        }
    }

    fun batchOrders(): ContractRoute {
        val requestBody = Body.auto<BatchOrdersApiRequest>().toLens()
        val responseBody = Body.auto<OrdersApiResponse>().toLens()

        return "batch/orders" meta {
            operationId = "batch-orders"
            summary = "Manage orders in batch"
            security = signedTokenSecurity
        } bindContract Method.POST to { request ->
            val apiRequest: BatchOrdersApiRequest = requestBody(request)

            logger.debug {
                Json.encodeToString(apiRequest)
            }

            // TODO: broadcast affected orders

            Response(Status.OK).with(
                responseBody of OrdersApiResponse(
                    emptyList(),
                ),
            )
        }
    }

    fun listTrades(): ContractRoute {
        val responseBody = Body.auto<TradesApiResponse>().toLens()

        return "trades" meta {
            operationId = "list-trades"
            summary = "List trades"
            security = signedTokenSecurity
            queries += Query.string().optional("before-timestamp", "Return trades executed before provided timestamp")
            queries += Query.string().optional("limit", "Number of trades to return")
        } bindContract Method.GET to { request ->
            val timestamp = request.query("before-timestamp")?.toInstant() ?: Instant.DISTANT_FUTURE
            val limit = request.query("limit")?.toInt() ?: 100

            val trades = transaction {
                OrderExecutionEntity.listExecutions(
                    wallet = WalletEntity.getOrCreate(request.principal),
                    beforeTimestamp = timestamp,
                    limit = limit,
                ).map { it.toTradeResponse() }
            }

            Response(Status.OK).with(
                responseBody of TradesApiResponse(trades = trades),
            )
        }
    }

    private fun validateSignature(market: MarketEntity?, walletAddress: Address, apiRequest: CreateOrderApiRequest, blockchainClient: BlockchainClient): Boolean {
        return market?.let {
            val tx = when (apiRequest) {
                is CreateOrderApiRequest.Market -> apiRequest.toEip712Transaction(walletAddress, it.baseSymbol.contractAddress, it.quoteSymbol.contractAddress)
                is CreateOrderApiRequest.Limit -> apiRequest.toEip712Transaction(walletAddress, it.baseSymbol.contractAddress, it.quoteSymbol.contractAddress, it.quoteSymbol.decimals.toInt())
            }

            val contractAddress = blockchainClient.getContractAddress(ContractType.Exchange)
            contractAddress?.let { verifyingContract ->
                ECHelper.isValidSignature(
                    EIP712Helper.computeHash(
                        tx,
                        blockchainClient.chainId,
                        verifyingContract,
                    ),
                    tx.signature,
                    walletAddress,
                )
            } ?: false
        } ?: false
    }
}
