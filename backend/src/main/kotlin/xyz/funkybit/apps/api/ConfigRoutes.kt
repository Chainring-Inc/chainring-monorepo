package xyz.funkybit.apps.api

import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Path
import org.http4k.lens.string
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.middleware.principal
import xyz.funkybit.apps.api.middleware.signedTokenSecurity
import xyz.funkybit.apps.api.model.AccountConfigurationApiResponse
import xyz.funkybit.apps.api.model.Chain
import xyz.funkybit.apps.api.model.ConfigurationApiResponse
import xyz.funkybit.apps.api.model.DeployedContract
import xyz.funkybit.apps.api.model.Market
import xyz.funkybit.apps.api.model.Role
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.FeeRate
import xyz.funkybit.core.model.Symbol
import xyz.funkybit.core.model.db.ChainEntity
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainTable
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.FeeRates
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import java.math.BigInteger

class ConfigRoutes(private val faucetMode: FaucetMode) {
    private val logger = KotlinLogging.logger {}

    val getConfiguration: ContractRoute = run {
        val responseBody = Body.auto<ConfigurationApiResponse>().toLens()

        "config" meta {
            operationId = "config"
            summary = "Get configuration"
            tags += listOf(Tag("configuration"))
            returning(
                Status.OK,
                responseBody to
                    ConfigurationApiResponse(
                        chains = listOf(
                            Chain(
                                id = ChainId(1337u),
                                name = "Bitlayer",
                                contracts = listOf(
                                    DeployedContract(
                                        name = "Exchange",
                                        address = Address("0x0000000000000000000000000000000000000000"),
                                    ),
                                ),
                                symbols = listOf(
                                    SymbolInfo(
                                        name = "ETH",
                                        description = "Ethereum",
                                        contractAddress = null,
                                        decimals = 18u,
                                        faucetSupported = false,
                                        iconUrl = "https://icons/eth.svg",
                                        withdrawalFee = BigInteger.ONE,
                                    ),
                                    SymbolInfo(
                                        name = "USDC",
                                        description = "USD Coin",
                                        contractAddress = Address("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                                        decimals = 18u,
                                        faucetSupported = false,
                                        iconUrl = "https://icons/usdc.svg",
                                        withdrawalFee = BigInteger.ONE,
                                    ),
                                ),
                                jsonRpcUrl = "https://demo-anvil.funkybit.fun",
                                blockExplorerNetName = "funkybit Demo BitLayer",
                                blockExplorerUrl = "https://demo-otterscan.funkybit.fun",
                                networkType = NetworkType.Evm,
                            ),
                        ),
                        markets = listOf(
                            Market(
                                id = MarketId("USDC/DAI"),
                                baseSymbol = Symbol("USDC"),
                                baseDecimals = 18,
                                quoteSymbol = Symbol("DAI"),
                                quoteDecimals = 6,
                                tickSize = "0.01".toBigDecimal(),
                                lastPrice = "0.995".toBigDecimal(),
                                minFee = BigInteger.ONE,
                            ),
                        ),
                        feeRates = FeeRates(
                            maker = FeeRate.fromPercents(1.0),
                            taker = FeeRate.fromPercents(2.0),
                        ),
                    ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                Response(Status.OK).with(
                    responseBody of
                        ConfigurationApiResponse(
                            chains = ChainEntity.all().orderBy(ChainTable.id to SortOrder.ASC).map { chain ->
                                Chain(
                                    id = chain.id.value,
                                    name = chain.name,
                                    contracts = DeployedSmartContractEntity.validContracts(chain.id.value).map {
                                        DeployedContract(
                                            name = it.name,
                                            address = it.proxyAddress,
                                        )
                                    },
                                    symbols = SymbolEntity.forChain(chain.id.value).map {
                                        SymbolInfo(
                                            name = it.name,
                                            description = it.description,
                                            contractAddress = it.contractAddress,
                                            decimals = it.decimals,
                                            faucetSupported = it.faucetSupported(faucetMode),
                                            iconUrl = it.iconUrl,
                                            withdrawalFee = it.withdrawalFee,
                                        )
                                    },
                                    jsonRpcUrl = chain.jsonRpcUrl,
                                    blockExplorerNetName = chain.blockExplorerNetName,
                                    blockExplorerUrl = chain.blockExplorerUrl,
                                    networkType = chain.networkType,
                                )
                            },
                            markets = MarketEntity.all().map { market ->
                                Market(
                                    id = market.id.value,
                                    baseSymbol = Symbol(market.baseSymbol.name),
                                    baseDecimals = market.baseSymbol.decimals.toInt(),
                                    quoteSymbol = Symbol(market.quoteSymbol.name),
                                    quoteDecimals = market.quoteSymbol.decimals.toInt(),
                                    tickSize = market.tickSize,
                                    lastPrice = market.lastPrice,
                                    minFee = market.minFee,
                                )
                            }.sortedWith(compareBy({ it.baseSymbol.value }, { it.quoteSymbol.value })),
                            feeRates = FeeRates.fetch(),
                        ),
                )
            }
        }
    }

    val getAccountConfiguration: ContractRoute = run {
        val responseBody = Body.auto<AccountConfigurationApiResponse>().toLens()

        "account-config" meta {
            operationId = "account-config"
            summary = "Get account configuration"
            security = signedTokenSecurity
            tags += listOf(Tag("configuration"))
            returning(
                Status.OK,
                responseBody to
                    AccountConfigurationApiResponse(
                        role = Role.User,
                        newSymbols = listOf(
                            SymbolInfo(
                                name = "RING",
                                description = "funkybit Token",
                                contractAddress = Address("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                                decimals = 18u,
                                faucetSupported = true,
                                iconUrl = "https://icons/ring.svg",
                                withdrawalFee = BigInteger.ZERO,
                            ),
                        ),
                    ),
            )
        } bindContract Method.GET to { request ->
            transaction {
                Response(Status.OK).with(
                    responseBody of
                        AccountConfigurationApiResponse(
                            role = if (WalletEntity.findByAddress(request.principal)?.isAdmin == true) Role.Admin else Role.User,
                            newSymbols = SymbolEntity.symbolsToAddToWallet(request.principal).map {
                                SymbolInfo(
                                    it.name,
                                    it.description,
                                    it.contractAddress,
                                    it.decimals,
                                    it.faucetSupported(faucetMode),
                                    it.iconUrl,
                                    it.withdrawalFee,
                                )
                            },
                        ),
                )
            }
        }
    }

    private val symbolNamePathParam = Path.string().of("symbolName", "Symbol Name")

    val markSymbolAsAdded: ContractRoute = run {
        "account-config" / symbolNamePathParam meta {
            operationId = "symbol-added"
            summary = "Mark symbol as added"
            security = signedTokenSecurity
            tags += listOf(Tag("configuration"))
            returning(
                Status.NO_CONTENT,
            )
        } bindContract Method.POST to { symbolName ->
            { request ->
                transaction {
                    WalletEntity.getOrCreate(request.principal).let {
                        if (!it.addedSymbols.contains(symbolName)) {
                            it.addedSymbols += symbolName
                        }
                    }
                    Response(Status.NO_CONTENT)
                }
            }
        }
    }
}
