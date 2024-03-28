package co.chainring.apps

import io.github.oshai.kotlinlogging.KLogger

abstract class BaseApp() {
    abstract val logger: KLogger

    abstract fun start()

    abstract fun stop()
}
