package co.chainring.integrationtests.utils

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import co.chainring.apps.api.middleware.SignInMessage
import co.chainring.apps.api.model.AccountConfigurationApiResponse
import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ApiErrors
import co.chainring.apps.api.model.BalancesApiResponse
import co.chainring.apps.api.model.BatchOrdersApiRequest
import co.chainring.apps.api.model.BatchOrdersApiResponse
import co.chainring.apps.api.model.CancelOrderApiRequest
import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.CreateDepositApiRequest
import co.chainring.apps.api.model.CreateOrderApiRequest
import co.chainring.apps.api.model.CreateOrderApiResponse
import co.chainring.apps.api.model.CreateWithdrawalApiRequest
import co.chainring.apps.api.model.DepositApiResponse
import co.chainring.apps.api.model.FaucetApiRequest
import co.chainring.apps.api.model.FaucetApiResponse
import co.chainring.apps.api.model.ListDepositsApiResponse
import co.chainring.apps.api.model.ListWithdrawalsApiResponse
import co.chainring.apps.api.model.Order
import co.chainring.apps.api.model.OrdersApiResponse
import co.chainring.apps.api.model.UpdateOrderApiRequest
import co.chainring.apps.api.model.UpdateOrderApiResponse
import co.chainring.apps.api.model.WithdrawalApiResponse
import co.chainring.core.blockchain.checksumAddress
import co.chainring.core.evm.ECHelper
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.DepositId
import co.chainring.core.model.db.MarketId
import co.chainring.core.model.db.OrderId
import co.chainring.core.model.db.OrderStatus
import co.chainring.core.model.db.WithdrawalId
import co.chainring.core.utils.TraceRecorder
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.internal.EMPTY_REQUEST
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.net.HttpURLConnection
import java.util.Base64

val apiServerRootUrl = System.getenv("API_URL") ?: "http://localhost:9000"
val httpClient = OkHttpClient.Builder().build()
val applicationJson = "application/json".toMediaType()

class AbnormalApiResponseException(val response: Response) : Exception()

data class ApiCallFailure(
    val httpCode: Int,
    val error: ApiError?,
)

open class ApiClient(val ecKeyPair: ECKeyPair = Keys.createEcKeyPair(), val traceRecorder: TraceRecorder = TraceRecorder.noOp) {
    val authToken: String = issueAuthToken(ecKeyPair = ecKeyPair)
    val address = Credentials.create(ecKeyPair).checksumAddress()

    companion object {
        private fun listOrdersUrl(statuses: List<OrderStatus>, marketId: MarketId?) = "$apiServerRootUrl/v1/orders".toHttpUrl().newBuilder().apply {
            addQueryParameter("statuses", statuses.joinToString(","))
            marketId?.let {
                addQueryParameter("marketId", it.value)
            }
        }.build()

        fun tryListOrders(statuses: List<OrderStatus> = emptyList(), marketId: MarketId? = null, authHeadersProvider: (Request) -> Headers): Either<ApiCallFailure, OrdersApiResponse> =
            execute(
                Request.Builder()
                    .url(listOrdersUrl(statuses, marketId))
                    .get()
                    .build().let {
                        it.addHeaders(authHeadersProvider(it))
                    },
            ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

        internal fun authHeaders(ecKeyPair: ECKeyPair): Headers {
            val didToken = issueAuthToken(ecKeyPair)

            return Headers.Builder()
                .add(
                    "Authorization",
                    "Bearer $didToken",
                ).build()
        }

        fun issueAuthToken(
            ecKeyPair: ECKeyPair = Keys.createEcKeyPair(),
            address: String = "0x${Keys.getAddress(ecKeyPair)}",
            chainId: ChainId = ChainId(1337U),
            timestamp: Instant = Clock.System.now(),
        ): String {
            val message = SignInMessage(
                message = "[ChainRing Labs] Please sign this message to verify your ownership of this wallet address. This action will not cost any gas fees.",
                address = address,
                chainId = chainId,
                timestamp = timestamp.toString(),
            )

            val body: String = Json.encodeToString(message)
            val signature: EvmSignature = ECHelper.signData(Credentials.create(ecKeyPair), EIP712Helper.computeHash(message))

            return "${Base64.getUrlEncoder().encodeToString(body.toByteArray())}.${signature.value}"
        }

        private fun execute(request: Request): Response =
            httpClient.newCall(request).execute()
    }

    fun newWebSocket(listener: WebSocketListener): WebSocket =
        httpClient.newWebSocket(
            Request
                .Builder()
                .url(apiServerRootUrl.replace("http:", "ws:").replace("https:", "wss:") + "/connect?auth=$authToken")
                .build(),
            listener,
        )

    fun tryGetConfiguration(): Either<ApiCallFailure, ConfigurationApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.GetConfiguration,
            Request.Builder()
                .url("$apiServerRootUrl/v1/config")
                .get()
                .build(),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryGetAccountConfiguration(): Either<ApiCallFailure, AccountConfigurationApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.GetAccountConfiguration,
            Request.Builder()
                .url("$apiServerRootUrl/v1/account-config")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryMarkSymbolAsAdded(symbolName: String): Either<ApiCallFailure, Unit> =
        executeAndTrace(
            TraceRecorder.Op.MarkSymbolAsAdded,
            Request.Builder()
                .url("$apiServerRootUrl/v1/account-config/$symbolName")
                .post(EMPTY_REQUEST)
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)

    fun tryCreateOrder(apiRequest: CreateOrderApiRequest): Either<ApiCallFailure, CreateOrderApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.CreateOrder,
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)

    fun tryBatchOrders(apiRequest: BatchOrdersApiRequest): Either<ApiCallFailure, BatchOrdersApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.BatchOrders,
            Request.Builder()
                .url("$apiServerRootUrl/v1/batch/orders")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryUpdateOrder(apiRequest: UpdateOrderApiRequest): Either<ApiCallFailure, UpdateOrderApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.UpdateOrder,
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/${apiRequest.orderId}")
                .patch(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryCancelOrder(apiRequest: CancelOrderApiRequest): Either<ApiCallFailure, Unit> =
        executeAndTrace(
            TraceRecorder.Op.CancelOrder,
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/${apiRequest.orderId}")
                .delete(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)

    fun tryGetOrder(id: OrderId): Either<ApiCallFailure, Order> =
        executeAndTrace(
            TraceRecorder.Op.GetOrder,
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders/$id")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(HttpURLConnection.HTTP_OK)

    fun tryListOrders(statuses: List<OrderStatus> = emptyList(), marketId: MarketId? = null): Either<ApiCallFailure, OrdersApiResponse> {
        return executeAndTrace(
            TraceRecorder.Op.ListOrders,
            Request.Builder()
                .url(listOrdersUrl(statuses, marketId))
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(HttpURLConnection.HTTP_OK)
    }

    fun tryCancelOpenOrders(): Either<ApiCallFailure, Unit> =
        executeAndTrace(
            TraceRecorder.Op.CancelOpenOrders,
            Request.Builder()
                .url("$apiServerRootUrl/v1/orders")
                .delete()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrUnit(expectedStatusCode = HttpURLConnection.HTTP_NO_CONTENT)

    fun tryCreateDeposit(apiRequest: CreateDepositApiRequest): Either<ApiCallFailure, DepositApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.CreateDeposit,
            Request.Builder()
                .url("$apiServerRootUrl/v1/deposits")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)

    fun tryGetDeposit(id: DepositId): Either<ApiCallFailure, DepositApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.GetWithdrawal,
            Request.Builder()
                .url("$apiServerRootUrl/v1/deposits/$id")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryListDeposits(): Either<ApiCallFailure, ListDepositsApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.ListDeposits,
            Request.Builder()
                .url("$apiServerRootUrl/v1/deposits")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryCreateWithdrawal(apiRequest: CreateWithdrawalApiRequest): Either<ApiCallFailure, WithdrawalApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.CreateWithdrawal,
            Request.Builder()
                .url("$apiServerRootUrl/v1/withdrawals")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_CREATED)

    fun tryGetWithdrawal(id: WithdrawalId): Either<ApiCallFailure, WithdrawalApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.GetWithdrawal,
            Request.Builder()
                .url("$apiServerRootUrl/v1/withdrawals/$id")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryListWithdrawals(): Either<ApiCallFailure, ListWithdrawalsApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.ListWithdrawals,
            Request.Builder()
                .url("$apiServerRootUrl/v1/withdrawals")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryGetBalances(): Either<ApiCallFailure, BalancesApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.GetBalances,
            Request.Builder()
                .url("$apiServerRootUrl/v1/balances")
                .get()
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    fun tryFaucet(apiRequest: FaucetApiRequest): Either<ApiCallFailure, FaucetApiResponse> =
        executeAndTrace(
            TraceRecorder.Op.GetBalances,
            Request.Builder()
                .url("$apiServerRootUrl/v1/faucet")
                .post(Json.encodeToString(apiRequest).toRequestBody(applicationJson))
                .build()
                .withAuthHeaders(ecKeyPair),
        ).toErrorOrPayload(expectedStatusCode = HttpURLConnection.HTTP_OK)

    private fun executeAndTrace(op: TraceRecorder.Op, request: Request): Response {
        return traceRecorder.record(op) {
            httpClient.newCall(request).execute()
        }
    }

    open fun getConfiguration(): ConfigurationApiResponse =
        tryGetConfiguration().throwOrReturn()

    open fun getAccountConfiguration(): AccountConfigurationApiResponse =
        tryGetAccountConfiguration().throwOrReturn()

    open fun markSymbolAsAdded(symbolName: String) =
        tryMarkSymbolAsAdded(symbolName).throwOrReturn()

    open fun createOrder(apiRequest: CreateOrderApiRequest): CreateOrderApiResponse =
        tryCreateOrder(apiRequest).throwOrReturn()

    open fun batchOrders(apiRequest: BatchOrdersApiRequest): BatchOrdersApiResponse =
        tryBatchOrders(apiRequest).throwOrReturn()

    open fun updateOrder(apiRequest: UpdateOrderApiRequest): UpdateOrderApiResponse {
        return tryUpdateOrder(apiRequest).throwOrReturn()
    }

    open fun cancelOrder(apiRequest: CancelOrderApiRequest) =
        tryCancelOrder(apiRequest).throwOrReturn()

    open fun getOrder(id: OrderId): Order =
        tryGetOrder(id).throwOrReturn()

    open fun listOrders(statuses: List<OrderStatus> = emptyList(), marketId: MarketId? = null): OrdersApiResponse =
        tryListOrders(statuses).throwOrReturn()

    open fun cancelOpenOrders() =
        tryCancelOpenOrders().throwOrReturn()

    open fun createDeposit(apiRequest: CreateDepositApiRequest): DepositApiResponse =
        tryCreateDeposit(apiRequest).throwOrReturn()

    open fun getDeposit(id: DepositId): DepositApiResponse =
        tryGetDeposit(id).throwOrReturn()

    open fun listDeposits(): ListDepositsApiResponse =
        tryListDeposits().throwOrReturn()

    open fun createWithdrawal(apiRequest: CreateWithdrawalApiRequest): WithdrawalApiResponse =
        tryCreateWithdrawal(apiRequest).throwOrReturn()

    open fun getWithdrawal(id: WithdrawalId): WithdrawalApiResponse =
        tryGetWithdrawal(id).throwOrReturn()

    open fun listWithdrawals(): ListWithdrawalsApiResponse =
        tryListWithdrawals().throwOrReturn()

    open fun getBalances(): BalancesApiResponse =
        tryGetBalances().throwOrReturn()

    open fun faucet(apiRequest: FaucetApiRequest) =
        tryFaucet(apiRequest).throwOrReturn()
}

fun <T> Either<ApiCallFailure, T>.throwOrReturn(): T {
    if (this.isLeft()) {
        throw Exception(this.leftOrNull()?.error?.displayMessage ?: "Unknown Error")
    }
    return this.getOrNull()!!
}

val json = Json {
    this.ignoreUnknownKeys = true
}

fun Request.withAuthHeaders(ecKeyPair: ECKeyPair?): Request =
    if (ecKeyPair == null) {
        this
    } else {
        addHeaders(ApiClient.authHeaders(ecKeyPair))
    }

inline fun <reified T> Response.toErrorOrPayload(expectedStatusCode: Int): Either<ApiCallFailure, T> {
    return either {
        val bodyString = body?.string()

        ensure(code == expectedStatusCode) {
            val apiError = bodyString?.let {
                try {
                    json.decodeFromString<ApiErrors>(bodyString).errors.single()
                } catch (e: Exception) {
                    null
                }
            }

            ApiCallFailure(code, apiError)
        }

        json.decodeFromString<T>(bodyString!!)
    }
}

fun Response.toErrorOrUnit(expectedStatusCode: Int): Either<ApiCallFailure, Unit> {
    return either {
        val bodyString = body?.string()

        ensure(code == expectedStatusCode) {
            val apiError = bodyString?.let {
                try {
                    json.decodeFromString<ApiErrors>(bodyString).errors.single()
                } catch (e: Exception) {
                    null
                }
            }

            ApiCallFailure(code, apiError)
        }
    }
}

fun Request.addHeaders(headers: Headers): Request =
    this
        .newBuilder()
        .headers(
            this
                .headers
                .newBuilder()
                .addAll(headers)
                .build(),
        ).build()

val Headers.Companion.empty: Headers
    get() = emptyMap<String, String>().toHeaders()
