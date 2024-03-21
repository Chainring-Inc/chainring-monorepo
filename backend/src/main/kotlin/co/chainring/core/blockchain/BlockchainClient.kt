package co.chainring.core.blockchain

import co.chainring.contracts.generated.ERC1967Proxy
import co.chainring.contracts.generated.Exchange
import co.chainring.contracts.generated.UUPSUpgradeable
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.model.db.DeployedSmartContractEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger

enum class ContractType {
    Exchange,
}

data class BlockchainClientConfig(
    val url: String = System.getenv("EVM_NETWORK_URL") ?: "http://localhost:8545",
    val privateKeyHex: String =
        System.getenv(
            "EVM_CONTRACT_MANAGEMENT_PRIVATE_KEY",
        ) ?: "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
    val deploymentPollingIntervalInMs: Long = longValue("DEPLOYMENT_POLLING_INTERVAL_MS", 1000L),
    val maxPollingAttempts: Long = longValue("MAX_POLLING_ATTEMPTS", 120L),
    val contractCreationLimit: BigInteger = bigIntegerValue("CONTRACT_CREATION_LIMIT", BigInteger.valueOf(5_000_000)),
    val contractInvocationLimit: BigInteger = bigIntegerValue("CONTRACT_INVOCATION_LIMIT", BigInteger.valueOf(1_000_000)),
    val defaultMaxPriorityFeePerGasInWei: BigInteger = bigIntegerValue("DEFAULT_MAX_PRIORITY_FEE_PER_GAS_WEI", BigInteger.valueOf(5_000_000_000)),
    val enableWeb3jLogging: Boolean = (System.getenv("ENABLE_WEB3J_LOGGING") ?: "true") == "true",
) {
    companion object {
        fun longValue(name: String, defaultValue: Long): Long {
            return try {
                System.getenv(name)?.toLong() ?: defaultValue
            } catch (e: Exception) {
                defaultValue
            }
        }

        fun bigIntegerValue(name: String, defaultValue: BigInteger): BigInteger {
            return try {
                System.getenv(name)?.let { BigInteger(it) } ?: defaultValue
            } catch (e: Exception) {
                defaultValue
            }
        }
    }
}

open class BlockchainClient(private val config: BlockchainClientConfig = BlockchainClientConfig()) {
    protected val web3j = Web3j.build(httpService(config.url, config.enableWeb3jLogging))
    protected val credentials = Credentials.create(config.privateKeyHex)
    val chainId = ChainId(web3j.ethChainId().send().chainId)
    protected val transactionManager = RawTransactionManager(
        web3j,
        credentials,
        chainId.value.toLong(),
        PollingTransactionReceiptProcessor(
            web3j,
            config.deploymentPollingIntervalInMs,
            config.maxPollingAttempts.toInt(),
        ),
    )

    protected val gasProvider = GasProvider(
        contractCreationLimit = config.contractCreationLimit,
        contractInvocationLimit = config.contractInvocationLimit,
        defaultMaxPriorityFeePerGas = config.defaultMaxPriorityFeePerGasInWei,
        chainId = chainId.toLong(),
        web3j = web3j,
    )

    companion object {

        val logger = KotlinLogging.logger {}
        fun httpService(url: String, logging: Boolean): HttpService {
            val builder = OkHttpClient.Builder()
            if (logging) {
                builder.addInterceptor {
                    val request = it.request()
                    val requestCopy = request.newBuilder().build()
                    val requestBuffer = Buffer()
                    requestCopy.body?.writeTo(requestBuffer)
                    logger.debug {
                        ">> ${request.method} ${request.url} | ${requestBuffer.readUtf8()}"
                    }
                    val response = it.proceed(request)
                    val contentType: MediaType? = response.body!!.contentType()
                    val content = response.body!!.string()
                    logger.debug {
                        "<< ${response.code} | $content"
                    }
                    response.newBuilder().body(content.toResponseBody(contentType)).build()
                }
            }
            return HttpService(url, builder.build())
        }
    }

    fun updateContracts() {
        transaction {
            DeployedSmartContractEntity.validContracts(chainId).map { it.name }

            ContractType.entries.forEach {
                DeployedSmartContractEntity.findLastDeployedContractByNameAndChain(ContractType.Exchange.name, chainId)?.let { deployedContract ->
                    if (deployedContract.deprecated) {
                        logger.info { "Upgrading contract: $it" }
                        deployOrUpgradeWithProxy(it, deployedContract.proxyAddress)
                    } else {
                        deployedContract
                    }
                } ?: run {
                    logger.info { "Deploying contract: $it" }
                    deployOrUpgradeWithProxy(it, null)
                }
            }
        }
    }

    private fun deployOrUpgradeWithProxy(
        contractType: ContractType,
        existingProxyAddress: Address?,
    ) {
        logger.debug { "Starting deployment for $contractType" }
        val (implementationContractAddress, version) =
            when (contractType) {
                ContractType.Exchange -> {
                    val implementationContract = Exchange.deploy(web3j, transactionManager, gasProvider).send()
                    Pair(
                        Address(implementationContract.contractAddress),
                        implementationContract.version.send().toInt(),
                    )
                }
            }
        val proxyAddress =
            if (existingProxyAddress != null) {
                // for now call upgradeTo here
                logger.debug { "Calling upgradeTo for $contractType" }
                UUPSUpgradeable.load(
                    existingProxyAddress.value,
                    web3j,
                    transactionManager,
                    gasProvider,
                ).upgradeToAndCall(implementationContractAddress.value, ByteArray(0), BigInteger.ZERO).send()
                existingProxyAddress
            } else {
                // deploy the proxy and call the initialize method in contract - this can only be called once
                logger.debug { "Deploying proxy for $contractType" }
                val proxyContract =
                    ERC1967Proxy.deploy(
                        web3j,
                        transactionManager,
                        gasProvider,
                        BigInteger.ZERO,
                        implementationContractAddress.value,
                        ByteArray(0),
                    ).send()
                logger.debug { "Deploying initialize for $contractType" }
                when (contractType) {
                    ContractType.Exchange -> {
                        Exchange.load(proxyContract.contractAddress, web3j, transactionManager, gasProvider).initialize().send()
                    }
                }
                Address(proxyContract.contractAddress)
            }

        logger.debug { "Creating db entry for $contractType" }
        DeployedSmartContractEntity.create(
            name = contractType.name,
            chainId = chainId,
            implementationAddress = implementationContractAddress,
            proxyAddress = proxyAddress,
            version = version,
        )
        logger.debug { "Deployment complete for $contractType" }
    }

    fun loadExchangeContract(address: Address): Exchange {
        return Exchange.load(address.value, web3j, transactionManager, gasProvider)
    }
}
