package xyz.funkybit.apps.telegrambot

import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.apps.api.model.CreateDepositApiRequest
import xyz.funkybit.apps.api.model.CreateWithdrawalApiRequest
import xyz.funkybit.apps.api.model.RequestProcessingError
import xyz.funkybit.apps.api.services.ExchangeApiService
import xyz.funkybit.apps.telegrambot.model.Input
import xyz.funkybit.apps.telegrambot.model.Output
import xyz.funkybit.apps.telegrambot.model.OutputMessage
import xyz.funkybit.apps.telegrambot.model.SwapEstimateError
import xyz.funkybit.apps.telegrambot.model.estimateSwap
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.model.abbreviated
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.OrderStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.telegram.bot.SessionState
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserEntity
import xyz.funkybit.core.model.telegram.bot.TelegramBotUserWalletEntity
import xyz.funkybit.core.model.telegram.bot.TelegramMessageId
import xyz.funkybit.core.utils.setScale
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger

class InputHandler(
    private val outputChannel: BotApp.OutputChannel,
    private val exchangeApiService: ExchangeApiService,
) {
    private val logger = KotlinLogging.logger { }

    fun handle(input: Input) {
        val user = TelegramBotUserEntity.getOrCreate(input.from)
        val currentWallet = user.currentWallet()
        val symbols = SymbolEntity.all().filter { it.chain.networkType == NetworkType.Evm }.sortedBy { it.name }

        when (val currentState = user.sessionState) {
            is SessionState.Initial -> {
                when (input) {
                    is Input.Start -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.MainMenu -> {
                when (input) {
                    is Input.Start -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                    }
                    is Input.Airdrop -> {
                        sendTransient(OutputMessage.selectSymbolToAirdrop(user, symbols.filter { it.contractAddress == null }))
                        user.updateSessionState(SessionState.AirdropSymbolSelection)
                    }
                    is Input.Deposit -> {
                        sendTransient(OutputMessage.selectSymbolToDeposit(user, symbols))
                        user.updateSessionState(SessionState.DepositSymbolSelection)
                    }
                    is Input.Withdraw -> {
                        sendTransient(OutputMessage.selectSymbolToWithdraw(user, symbols))
                        user.updateSessionState(SessionState.WithdrawalSymbolSelection)
                    }
                    is Input.Swap -> {
                        sendTransient(OutputMessage.selectSymbolToSwapFrom(user, symbols))
                        user.updateSessionState(SessionState.SwapFromSymbolSelection)
                    }
                    is Input.Settings -> {
                        sendTransient(OutputMessage.settings(user, currentWallet.address))
                        user.updateSessionState(SessionState.Settings)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.AirdropSymbolSelection -> {
                when (input) {
                    is Input.SymbolSelected -> {
                        val symbol = SymbolEntity.forName(input.symbol)
                        deleteTransientMessages(user)
                        val amount = BigDecimal("0.1").setScale(symbol.decimals)

                        val txHash = ChainManager
                            .getBlockchainClient(symbol.chainId.value)
                            .sendNativeDepositTx(currentWallet.address, amount.toFundamentalUnits(symbol.decimals))

                        send(OutputMessage.airdropRequested(user, symbol, amount))
                        user.updateSessionState(SessionState.AirdropPending(symbol, amount, txHash))
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.AirdropPending -> {
                when (input) {
                    is Input.AirdropTxReceipt -> {
                        if (input.receipt.isStatusOK) {
                            send(OutputMessage.airdropSucceeded(user, currentState.symbol, currentState.amount))
                        } else {
                            send(OutputMessage.airdropTxFailed(user, input.receipt))
                        }
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }
                    else -> { }
                }
            }

            is SessionState.DepositSymbolSelection -> {
                when (input) {
                    is Input.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        val available = currentWallet.onChainBalance(symbol)
                        if (available > BigDecimal.ZERO) {
                            send(OutputMessage.enterDepositAmount(user, symbol, available))
                            user.updateSessionState(SessionState.DepositAmountEntry(symbol))
                        } else {
                            sendTransient(OutputMessage.noBalanceInWallet(user, input.symbol))
                            sendTransient(OutputMessage.selectSymbolToDeposit(user, symbols))
                        }
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.DepositAmountEntry -> {
                when (input) {
                    is Input.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(SessionState.MainMenu)
                        } else {
                            val available = currentWallet.onChainBalance(currentState.symbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(OutputMessage.invalidNumber(user, input.text))
                            } else if (amount.signum() <= 0) {
                                send(OutputMessage.amountHasToBePositive(user))
                            } else if (amount > available) {
                                send(OutputMessage.depositAmountTooLarge(user))
                                send(OutputMessage.enterDepositAmount(user, currentState.symbol, available))
                            } else {
                                sendTransient(OutputMessage.depositConfirmation(user, currentState.symbol, amount))
                                user.updateSessionState(SessionState.DepositConfirmation(currentState.symbol, amount))
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.DepositConfirmation -> {
                when (input) {
                    is Input.Confirm -> {
                        try {
                            deleteTransientMessages(user)
                            val depositId = currentWallet
                                .deposit(currentState.amount, currentState.symbol)
                                ?.let { txHash ->
                                    exchangeApiService
                                        .deposit(currentWallet.address, CreateDepositApiRequest(currentState.symbol, currentState.amount, txHash))
                                        .deposit.id
                                }

                            if (depositId == null) {
                                send(OutputMessage.depositFailed(user, currentState.symbol))
                                user.updateSessionState(SessionState.MainMenu)
                            } else {
                                send(OutputMessage.depositInProgress(user))
                                user.updateSessionState(SessionState.DepositPending(depositId))
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Deposit failed" }
                            send(OutputMessage.depositFailed(user, currentState.symbol))
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(SessionState.MainMenu)
                        }
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    is Input.ChangeAmount -> {
                        deleteTransientMessages(user)
                        sendTransient(OutputMessage.enterDepositAmount(user, currentState.symbol, currentWallet.onChainBalance(currentState.symbol)))
                        user.updateSessionState(SessionState.DepositAmountEntry(currentState.symbol))
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.DepositPending -> {
                when (input) {
                    is Input.DepositCompleted -> {
                        val deposit = input.deposit
                        if (deposit.status == DepositStatus.Complete) {
                            send(OutputMessage.depositSucceeded(user, deposit, currentWallet.exchangeAvailableBalance(deposit.symbol)))
                        } else {
                            send(OutputMessage.depositFailed(user, deposit.symbol))
                        }
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }
                    else -> {}
                }
            }

            is SessionState.WithdrawalSymbolSelection -> {
                when (input) {
                    is Input.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        val available = currentWallet.exchangeAvailableBalance(symbol)
                        if (available == BigInteger.ZERO) {
                            sendTransient(OutputMessage.noBalanceAvailable(user, symbol))
                            sendTransient(OutputMessage.selectSymbolToWithdraw(user, symbols))
                        } else {
                            send(OutputMessage.enterWithdrawalAmount(user, symbol, available))
                            user.updateSessionState(SessionState.WithdrawalAmountEntry(symbol))
                        }
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.WithdrawalAmountEntry -> {
                when (input) {
                    is Input.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(SessionState.MainMenu)
                        } else {
                            val available = currentWallet.exchangeAvailableBalance(currentState.symbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(OutputMessage.invalidNumber(user, input.text))
                            } else if (amount.signum() <= 0) {
                                send(OutputMessage.amountHasToBePositive(user))
                            } else if (amount > available) {
                                send(OutputMessage.withdrawalAmountTooLarge(user))
                                send(OutputMessage.enterWithdrawalAmount(user, currentState.symbol, available))
                            } else {
                                sendTransient(OutputMessage.withdrawalConfirmation(user, currentState.symbol, amount))
                                user.updateSessionState(SessionState.WithdrawalConfirmation(currentState.symbol, amount))
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.WithdrawalConfirmation -> {
                when (input) {
                    is Input.Confirm -> {
                        deleteTransientMessages(user)

                        val errorOrWithdrawalId = try {
                            val nonce = System.currentTimeMillis()
                            val signature = currentWallet.signWithdrawal(currentState.amount, currentState.symbol, nonce)
                            exchangeApiService
                                .withdraw(
                                    currentWallet.address,
                                    CreateWithdrawalApiRequest(currentState.symbol, currentState.amount, nonce, signature),
                                )
                                .withdrawal.id.right()
                        } catch (e: RequestProcessingError) {
                            e.error.left()
                        }

                        errorOrWithdrawalId
                            .onLeft { error ->
                                send(OutputMessage.withdrawalFailed(user, error))
                                sendMainMenu(currentWallet, symbols)
                                user.updateSessionState(SessionState.MainMenu)
                            }
                            .onRight { withdrawalId ->
                                send(OutputMessage.withdrawalInProgress(user))
                                user.updateSessionState(SessionState.WithdrawalPending(withdrawalId))
                            }
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    is Input.ChangeAmount -> {
                        deleteTransientMessages(user)
                        sendTransient(OutputMessage.enterWithdrawalAmount(user, currentState.symbol, currentWallet.exchangeAvailableBalance(currentState.symbol)))
                        user.updateSessionState(SessionState.WithdrawalAmountEntry(currentState.symbol))
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.WithdrawalPending -> {
                when (input) {
                    is Input.WithdrawalCompleted -> {
                        val withdrawal = input.withdrawal
                        if (withdrawal.status == WithdrawalStatus.Complete) {
                            send(OutputMessage.withdrawalSucceeded(user))
                        } else {
                            send(OutputMessage.withdrawalFailed(user, null))
                        }
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }
                    else -> {}
                }
            }

            is SessionState.SwapFromSymbolSelection -> {
                when (input) {
                    is Input.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        val available = currentWallet.exchangeAvailableBalance(symbol)
                        if (available > BigDecimal.ZERO) {
                            sendTransient(OutputMessage.selectSymbolToSwapTo(user, symbol.swapOptions(), from = symbol))
                            user.updateSessionState(SessionState.SwapToSymbolSelection(fromSymbol = symbol))
                        } else {
                            sendTransient(OutputMessage.noBalanceAvailable(user, symbol))
                            sendTransient(OutputMessage.selectSymbolToSwapFrom(user, symbols))
                        }
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.SwapToSymbolSelection -> {
                when (input) {
                    is Input.SymbolSelected -> {
                        deleteTransientMessages(user)
                        val symbol = SymbolEntity.forName(input.symbol)
                        send(OutputMessage.enterSwapAmount(user, currentState.fromSymbol, currentWallet.exchangeAvailableBalance(currentState.fromSymbol), symbol))
                        user.updateSessionState(SessionState.SwapAmountEntry(fromSymbol = currentState.fromSymbol, toSymbol = symbol))
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.SwapAmountEntry -> {
                when (input) {
                    is Input.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(SessionState.MainMenu)
                        } else {
                            val available = currentWallet.exchangeAvailableBalance(currentState.fromSymbol)
                            val amount = runCatching { BigDecimal(input.text) }.getOrNull()
                            if (amount == null) {
                                send(OutputMessage.invalidNumber(user, input.text))
                            } else if (amount.signum() <= 0) {
                                send(OutputMessage.amountHasToBePositive(user))
                            } else if (amount > available) {
                                send(OutputMessage.swapAmountTooLarge(user))
                                send(OutputMessage.enterSwapAmount(user, currentState.fromSymbol, available, currentState.fromSymbol))
                            } else {
                                try {
                                    val swapEstimation = estimateSwap(currentState.fromSymbol, currentState.toSymbol, amount)
                                    sendTransient(OutputMessage.swapConfirmation(user, swapEstimation))
                                    user.updateSessionState(SessionState.SwapConfirmation(currentState.fromSymbol, currentState.toSymbol, amount))
                                } catch (e: SwapEstimateError) {
                                    send(OutputMessage.swapEstimateError(user, e))
                                    send(OutputMessage.enterSwapAmount(user, currentState.fromSymbol, available, currentState.fromSymbol))
                                }
                            }
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.SwapConfirmation -> {
                when (input) {
                    is Input.Confirm -> {
                        deleteTransientMessages(user)

                        try {
                            val swapEstimation = estimateSwap(currentState.fromSymbol, currentState.toSymbol, currentState.amount)
                            send(OutputMessage.submittingSwap(user, swapEstimation))

                            val signedOrder = swapEstimation.toOrderRequest().let { order ->
                                val (signature, verifyingChainId) = currentWallet.signOrder(swapEstimation.market, order.side, order.amount, order.nonce)
                                order.copy(signature = signature, verifyingChainId = verifyingChainId)
                            }

                            val errorOrOrderId = try {
                                val response = exchangeApiService.addOrder(currentWallet.address, signedOrder)
                                response.error?.left() ?: response.orderId.right()
                            } catch (e: RequestProcessingError) {
                                e.error.left()
                            }

                            errorOrOrderId
                                .onLeft { error ->
                                    send(OutputMessage.swapFailed(user, error))
                                    sendMainMenu(currentWallet, symbols)
                                    user.updateSessionState(SessionState.MainMenu)
                                }
                                .onRight { orderId ->
                                    user.updateSessionState(SessionState.SwapPending(orderId))
                                }
                        } catch (e: SwapEstimateError) {
                            send(OutputMessage.swapEstimateError(user, e))
                            sendMainMenu(currentWallet, symbols)
                            user.updateSessionState(SessionState.MainMenu)
                        }
                    }

                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }

                    is Input.ChangeAmount -> {
                        deleteTransientMessages(user)
                        sendTransient(OutputMessage.enterSwapAmount(user, currentState.fromSymbol, currentWallet.exchangeAvailableBalance(currentState.fromSymbol), currentState.toSymbol))
                        user.updateSessionState(SessionState.SwapAmountEntry(fromSymbolId = currentState.fromSymbolId, toSymbolId = currentState.toSymbolId))
                    }

                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.SwapPending -> {
                when (input) {
                    is Input.SwapCompleted -> {
                        val order = input.order
                        send(
                            when (order.status) {
                                OrderStatus.Filled -> OutputMessage.swapSucceeded(user, full = true)
                                OrderStatus.Partial -> OutputMessage.swapSucceeded(user, full = false)
                                OrderStatus.Rejected -> OutputMessage.swapRejected(user)
                                else -> OutputMessage.swapFailed(user, null)
                            },
                        )
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }
                    else -> { }
                }
            }

            is SessionState.Settings -> {
                when (input) {
                    is Input.SwitchWallet -> {
                        deleteTransientMessages(user)
                        sendTransient(
                            OutputMessage.selectWalletToSwitchTo(
                                user,
                                wallets = user.wallets.map { it.address },
                                currentWallet = currentWallet.address,
                            ),
                        )
                        user.updateSessionState(SessionState.WalletToSwitchToSelection)
                    }
                    is Input.ImportWallet -> {
                        deleteTransientMessages(user)
                        send(OutputMessage.importWalletPrivateKeyEntryPrompt(user))
                        user.updateSessionState(SessionState.ImportWalletPrivateKeyEntry)
                    }
                    is Input.ExportPrivateKey -> {
                        deleteTransientMessages(user)
                        sendTransient(OutputMessage.showPrivateKey(user, currentWallet.encryptedPrivateKey))
                        user.updateSessionState(SessionState.ShowingPrivateKey)
                    }
                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        sendMainMenu(currentWallet, symbols)
                        user.updateSessionState(SessionState.MainMenu)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.ShowingPrivateKey -> {
                deleteTransientMessages(user)
                sendTransient(OutputMessage.settings(user, currentWallet.address))
                user.updateSessionState(SessionState.Settings)
            }

            is SessionState.WalletToSwitchToSelection -> {
                when (input) {
                    is Input.WalletSelected -> {
                        deleteTransientMessages(user)
                        val targetWallet = user.wallets.find { it.address.abbreviated() == input.abbreviatedAddress }!!
                        if (currentWallet != targetWallet) {
                            currentWallet.isCurrent = false
                            targetWallet.isCurrent = true
                        }
                        send(OutputMessage.switchedToWallet(user, targetWallet.address))
                        sendTransient(OutputMessage.settings(user, targetWallet.address))
                        user.updateSessionState(SessionState.Settings)
                    }
                    is Input.Cancel -> {
                        deleteTransientMessages(user)
                        sendTransient(OutputMessage.settings(user, currentWallet.address))
                        user.updateSessionState(SessionState.Settings)
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }

            is SessionState.ImportWalletPrivateKeyEntry -> {
                when (input) {
                    is Input.Text -> {
                        if (input.text.contains("cancel", ignoreCase = true)) {
                            deleteTransientMessages(user)
                            sendTransient(OutputMessage.settings(user, currentWallet.address))
                            user.updateSessionState(SessionState.Settings)
                        } else {
                            try {
                                val newCurrentWallet = user.addWallet(input.text)
                                currentWallet.isCurrent = false
                                newCurrentWallet.isCurrent = true
                                send(OutputMessage.importWalletSuccess(user, newCurrentWallet.address))
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to import key" }
                                send(OutputMessage.importWalletFailure(user))
                            }
                            user.messageIdsForDeletion += input.messageId
                            deleteTransientMessages(user)
                            sendTransient(OutputMessage.settings(user, user.currentWallet().address))
                            user.updateSessionState(SessionState.Settings)
                        }
                    }
                    else -> {
                        sendInvalidCommand(user)
                    }
                }
            }
        }
    }

    private fun sendInvalidCommand(user: TelegramBotUserEntity) {
        sendTransient(OutputMessage.invalidCommand(user))
    }

    private fun sendMainMenu(wallet: TelegramBotUserWalletEntity, symbols: List<SymbolEntity>) =
        send(
            OutputMessage.mainMenu(
                wallet.user,
                wallet.address,
                walletOnChainBalances = wallet.onChainBalances(symbols),
                exchangeBalances = wallet.exchangeBalances(),
                airdropSupported = faucetSupported,
            ),
        )

    private fun deleteTransientMessages(user: TelegramBotUserEntity) {
        user.messageIdsForDeletion.forEach { messageId ->
            outputChannel.deleteMessage(
                Output.DeleteMessage(
                    user.telegramUserId.value.toString(),
                    messageId,
                ),
            )
        }
        user.messageIdsForDeletion = emptyList()
    }

    private fun send(message: Output.SendMessage): TelegramMessageId =
        outputChannel.sendMessage(message)

    private fun sendTransient(message: Output.SendMessage) {
        val sentMessageId = send(message)
        TelegramBotUserEntity.getOrCreate(message.recipient).messageIdsForDeletion += sentMessageId
    }
}
