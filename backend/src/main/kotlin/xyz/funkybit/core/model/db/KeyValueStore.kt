package xyz.funkybit.core.model.db

import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.upsert
import java.math.BigInteger

object KeyValueStore : Table("key_value_store") {

    var key = varchar("key", length = 10485760).uniqueIndex()
    var value = varchar("value", length = 10485760).nullable()

    fun getValue(key: String): String? {
        return select(value)
            .where(KeyValueStore.key.eq(key)).singleOrNull()
            ?.let { it[value] }
    }

    fun setValue(key: String, value: String?) {
        KeyValueStore.upsert {
            it[KeyValueStore.key] = key
            it[KeyValueStore.value] = value
        }
    }

    fun getLong(key: String): Long? {
        return getValue(key)?.toLong()
    }

    fun setLong(key: String, value: Long) {
        setValue(key, value.toString())
    }

    fun incrementLong(key: String): Long {
        val incrementedValue = (getLong(key) ?: 0L) + 1
        setLong(key, incrementedValue)
        return incrementedValue
    }

    fun getInstant(key: String): Instant? {
        return getValue(key)?.toLong()?.let { Instant.fromEpochMilliseconds(it) }
    }

    fun setInstant(key: String, value: Instant) {
        setValue(key, value.toEpochMilliseconds().toString())
    }

    fun getBoolean(key: String): Boolean {
        return getValue(key).toBoolean()
    }

    fun setBoolean(key: String, value: Boolean) {
        setValue(key, value.toString())
    }

    fun getBigInt(key: String): BigInteger? {
        return getValue(key)?.toBigInteger()
    }

    fun setBigInt(key: String, value: BigInteger) {
        setValue(key, value.toString())
    }

    fun remove(key: String) {
        KeyValueStore.deleteWhere {
            KeyValueStore.key.eq(key)
        }
    }
}
