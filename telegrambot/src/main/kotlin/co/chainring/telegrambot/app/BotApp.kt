package co.chainring.telegrambot.app

import co.chainring.apps.BaseApp
import co.chainring.core.db.DbConfig
import io.github.oshai.kotlinlogging.KotlinLogging

private val botToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: "6650191369:AAEwYfYkRjd3pBf7OEHmCIQ6MZZ1Wgygv6Q"
val faucetSupported = System.getenv("FAUCET_SUPPORTED")?.toBoolean() ?: true

class BotApp : BaseApp(dbConfig = DbConfig()) {
    override val logger = KotlinLogging.logger {}

    private val bot = Bot(BotTelegramClient(botToken))

    override fun start() {
        logger.info { "Starting" }
        super.start()
        bot.start()
    }

    override fun stop() {
        logger.info { "Stopping" }
        bot.stop()
        logger.info { "Stopped" }
    }
}
