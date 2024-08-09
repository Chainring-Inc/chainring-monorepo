package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.apps.api.BaseApp
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.db.DbConfig
import xyz.funkybit.core.db.migrations
import xyz.funkybit.core.db.upgrade
import xyz.funkybit.core.repeater.Repeater
import xyz.funkybit.core.sequencer.SequencerClient

data class RingAppConfig(
    val dbConfig: DbConfig = DbConfig(),
)

class RingApp(config: RingAppConfig = RingAppConfig()) : BaseApp(config.dbConfig) {
    override val logger = KotlinLogging.logger {}
    private val blockchainClients = ChainManager.getBlockchainClients()
    private val sequencerClient = SequencerClient()

    private val chainWorkers = blockchainClients.map { ChainWorker(it, sequencerClient) }
    private val settlementCoordinator = SettlementCoordinator(blockchainClients, sequencerClient)

    private val repeater = Repeater(db)

    override fun start() {
        logger.info { "Starting" }
        super.start()

        db.upgrade(migrations, logger)

        chainWorkers.forEach(ChainWorker::start)
        BitcoinChainWorker().start()
        settlementCoordinator.start()
        repeater.start()

        logger.info { "Started" }
    }

    override fun stop() {
        logger.info { "Stopping" }

        repeater.stop()
        settlementCoordinator.stop()
        chainWorkers.forEach(ChainWorker::stop)

        super.stop()
        logger.info { "Stopped" }
    }
}
