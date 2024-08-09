package xyz.funkybit

import xyz.funkybit.apps.api.ApiApp
import xyz.funkybit.apps.ring.RingApp
import xyz.funkybit.apps.telegrambot.BotApp
import xyz.funkybit.tasks.blockchainClients
import xyz.funkybit.tasks.fixtures.getFixtures
import xyz.funkybit.tasks.migrateDatabase
import xyz.funkybit.tasks.seedBlockchain
import xyz.funkybit.tasks.seedDatabase
import xyz.funkybit.tasks.seedSequencer
import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.ChainManager
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val logger = KotlinLogging.logger {}

    when (args.firstOrNull()) {
        "db:migrate" -> migrateDatabase()
        "db:seed" -> {
            val fixtures = getFixtures(blockchainClients, ChainManager.bitcoinBlockchainClientConfig)
            val symbolContractAddresses = seedBlockchain(fixtures)
            seedDatabase(fixtures, symbolContractAddresses)
            seedSequencer(fixtures)
        }
        else -> {
            logger.info { "Starting all apps" }

            try {
                RingApp().start()
                ApiApp().start()
                BotApp().start()
            } catch (e: Throwable) {
                logger.error(e) { "Failed to start" }
                exitProcess(1)
            }
        }
    }
}


