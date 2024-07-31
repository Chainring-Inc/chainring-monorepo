package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient

class BitcoinChainWorker {
    val logger = KotlinLogging.logger {}

    fun start() {
        if (BitcoinClient.bitcoinConfig.enabled) {
            try {
                BitcoinContractsPublisher.updateContracts()
            } catch (e: Exception) {
                logger.error(e) { "Failed to load bitcoin contract" }
            }
        }
    }

    fun stop() {
    }
}
