package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.ChainIdColumnType
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V72_AddNetworkType : Migration() {

    @Suppress("ClassName")
    enum class V72_NetworkType {
        Evm,
        Bitcoin,
    }

    @Suppress("ClassName")
    object V72_ChainTable : IdTable<ChainId>("chain") {
        override val id: Column<EntityID<ChainId>> = registerColumn<ChainId>(
            "id",
            ChainIdColumnType(),
        ).entityId()
        override val primaryKey = PrimaryKey(id)

        val networkType = customEnumeration(
            "network_type",
            "NetworkType",
            { value -> V72_NetworkType.valueOf(value as String) },
            { PGEnum("NetworkType", it) },
        ).default(V72_NetworkType.Evm)
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE NetworkType AS ENUM (${enumDeclaration<V72_NetworkType>()})")
            SchemaUtils.createMissingTablesAndColumns(V72_ChainTable)
        }
    }
}
