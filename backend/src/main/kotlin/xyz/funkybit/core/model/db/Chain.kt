package xyz.funkybit.core.model.db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.math.BigInteger

@Serializable
@JvmInline
value class ChainId(val value: ULong) : Comparable<ChainId> {
    constructor(bigInt: BigInteger) : this(bigInt.toLong().toULong())
    override fun toString(): String = value.toString()
    fun toLong(): Long = value.toLong()
    override fun compareTo(other: ChainId): Int = compareValuesBy(this, other) { it.value }

    companion object {
        val empty = ChainId(BigInteger.ZERO)
    }
}

class ChainIdColumnType : ColumnType() {
    override fun sqlType(): String = currentDialect.dataTypeProvider.uintegerType()
    override fun valueFromDB(value: Any): ChainId {
        return when (value) {
            is ULong -> ChainId(value)
            is Long -> ChainId(value.toULong())
            is Number -> ChainId(value.toLong().toULong())
            is String -> ChainId(value.toULong())
            else -> error("Unexpected value of type Int: $value of ${value::class.qualifiedName}")
        }
    }

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val v = when (value) {
            is UInt -> value.toLong()
            else -> value
        }
        super.setParameter(stmt, index, v)
    }

    override fun notNullValueToDB(value: Any): Any {
        val v = when (value) {
            is ChainId -> value.value.toLong()
            else -> value
        }
        return super.notNullValueToDB(v)
    }
}

@Serializable
enum class NetworkType {
    Evm,
    Bitcoin,
}

object ChainTable : IdTable<ChainId>("chain") {
    override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>("id", ChainIdColumnType()).entityId()
    override val primaryKey = PrimaryKey(id)

    val name = varchar("name", 10485760)
    val jsonRpcUrl = varchar("json_rpc_url", 10485760)
    val blockExplorerNetName = varchar("block_explorer_net_name", 10485760)
    val blockExplorerUrl = varchar("block_explorer_url", 10485760)
    val networkType = customEnumeration(
        "network_type",
        "NetworkType",
        { value -> NetworkType.valueOf(value as String) },
        { PGEnum("NetworkType", it) },
    )
}

class ChainEntity(id: EntityID<ChainId>) : Entity<ChainId>(id) {
    var name by ChainTable.name
    var jsonRpcUrl by ChainTable.jsonRpcUrl
    var blockExplorerNetName by ChainTable.blockExplorerNetName
    var blockExplorerUrl by ChainTable.blockExplorerUrl
    var networkType by ChainTable.networkType

    companion object : EntityClass<ChainId, ChainEntity>(ChainTable) {
        fun create(
            id: ChainId,
            name: String,
            jsonRpcUrl: String,
            blockExplorerNetName: String,
            blockExplorerUrl: String,
            networkType: NetworkType,
        ) = ChainEntity.new(id) {
            this.name = name
            this.jsonRpcUrl = jsonRpcUrl
            this.blockExplorerNetName = blockExplorerNetName
            this.blockExplorerUrl = blockExplorerUrl
            this.networkType = networkType
        }

        override fun all(): SizedIterable<ChainEntity> =
            wrapRows(table.selectAll().orderBy(table.id, SortOrder.ASC).notForUpdate())
    }
}
