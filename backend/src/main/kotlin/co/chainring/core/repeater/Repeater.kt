package co.chainring.core.repeater

import co.chainring.core.repeater.tasks.GasMonitorTask
import co.chainring.core.repeater.tasks.RepeaterBaseTask
import co.chainring.core.utils.PgListener
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

typealias Task = (args: List<String>) -> RepeaterBaseTask

class Repeater(db: Database) {
    private val logger = KotlinLogging.logger {}

    private val tasks = mapOf<String, Task>(
        "gas_monitor" to { args -> if (args.isNotEmpty()) GasMonitorTask(args[0].toBigInteger()) else GasMonitorTask() },
    )

    private val timer = ScheduledThreadPoolExecutor(8)

    private val pgListener = PgListener(db, "repeater_app_task-listener", "repeater_app_task_ctl", {}) { notification ->
        var repeaterTaskName = notification.parameter
        val args = mutableListOf<String>()
        if (repeaterTaskName.contains(':')) {
            val parts = repeaterTaskName.split(':')
            repeaterTaskName = parts[0]
            args.addAll(parts.slice(1..parts.lastIndex))
        }

        tasks[repeaterTaskName]?.let {
            logger.debug { "Scheduling one time $repeaterTaskName repeater task" }

            timer.schedule(it(args), 0, TimeUnit.NANOSECONDS)
        }
    }

    fun start() {
        logger.info { "Starting" }
        tasks.values.forEach {
            it(emptyList()).schedule(timer)
        }
        pgListener.start()
        logger.info { "Started" }
    }

    fun stop() {
        logger.info { "Stopping" }
        pgListener.stop()
        timer.shutdown()
        while (!timer.awaitTermination(5, TimeUnit.SECONDS)) {
            logger.info { "Awaiting termination" }
        }
        logger.info { "Stopped" }
    }
}
