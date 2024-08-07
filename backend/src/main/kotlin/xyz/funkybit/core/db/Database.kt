package xyz.funkybit.core.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.dbcp2.BasicDataSource
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.lang.System.getenv
import java.sql.ResultSet
import java.time.Duration

@Serializable
data class DbCredentials(
    val username: String,
    val password: String,
)

data class DbConfig(
    val credentials: DbCredentials =
        getenv("DB_CREDENTIALS")?.let { Json.decodeFromString<DbCredentials>(it) }
            ?: getenv("DB_PASSWORD")?.let {
                DbCredentials(
                    username = "funkybit",
                    password = it,
                )
            }
            ?: DbCredentials(
                username = "funkybit",
                password = "funkybit",
            ),
    val name: String = getenv("DB_NAME") ?: "funkybit",
    val driver: String = "org.postgresql.Driver",
    val host: String = getenv("DB_HOST") ?: "localhost",
    val port: Int = getenv("DB_PORT")?.toIntOrNull() ?: 5432,
    val initialPoolSize: Int = getenv("DB_INITIAL_POOL_SIZE")?.toIntOrNull() ?: 1,
    val minIdleConnections: Int = getenv("DB_MIN_IDLE_CONNECTIONS")?.toIntOrNull() ?: 15,
    val maxIdleConnections: Int = getenv("DB_MAX_IDLE_CONNECTIONS")?.toIntOrNull() ?: 25,
    val maxConnections: Int = getenv("DB_MAX_CONNECTIONS")?.toIntOrNull() ?: 25,
    val maxConnectionWaitingTimeMs: Long = getenv(
        "DB_MAX_CONNECTION_WAITING_TIME_MS",
    )?.toLongOrNull() ?: 10_0000,
    val validationQuery: String = getenv("DB_VALIDATION_QUERY") ?: "select 1",
)

fun Database.Companion.connect(config: DbConfig): Database =
    connect(
        BasicDataSource().apply {
            driverClassName = config.driver
            url = "jdbc:postgresql://${config.host}:${config.port}/${config.name}"
            this.password = config.credentials.password
            this.username = config.credentials.username
            validationQuery = config.validationQuery
            initialSize = config.initialPoolSize
            minIdle = config.minIdleConnections
            maxIdle = config.maxIdleConnections
            maxTotal = config.maxConnections
            setMaxWait(Duration.ofMillis(config.maxConnectionWaitingTimeMs))
        },
        databaseConfig = DatabaseConfig.Companion.invoke {
            defaultRepetitionAttempts = 1
            useNestedTransactions = true
        },
    )

fun Transaction.notifyDbListener(
    channel: String,
    payload: String? = null,
) {
    exec(
        listOfNotNull(
            "NOTIFY $channel",
            payload?.let { "'$payload'" },
        ).joinToString(", "),
    )
}

fun <T : Entity<Q>, Q : Comparable<Q>> Transaction.executeRaw(sql: String, entity: EntityClass<Q, T>): List<T> {
    val result = arrayListOf<T>()
    TransactionManager.current().exec(sql) { rs ->
        while (rs.next()) {
            result += entityFromResultSet(rs, entity)
        }
    }
    return result
}

fun <T : Entity<Q>, Q : Comparable<Q>> Transaction.entityFromResultSet(rs: ResultSet, entity: EntityClass<Q, T>): T =
    entity.wrapRow(ResultRow.create(rs, entity.table.columns.mapIndexed { index, field -> field to index }.toMap()))
