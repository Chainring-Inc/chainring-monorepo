package xyz.funkybit.core.model.db.migrations

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.db.Migration
import xyz.funkybit.core.model.db.ContractId
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.PGEnum
import xyz.funkybit.core.model.db.enumDeclaration

@Suppress("ClassName")
class V1_DeployedSmartContract : Migration() {
    enum class V1_Chain {
        Ethereum,
    }

    private object V1_DeployedSmartContract : GUIDTable<ContractId>("deployed_smart_contract", ::ContractId) {
        val createdAt = timestamp("created_at")
        val createdBy = varchar("created_by", 10485760)
        val name = varchar("name", 10485760)
        val chain =
            customEnumeration(
                "chain",
                "Chain",
                { value -> V1_Chain.valueOf(value as String) },
                { PGEnum("Chain", it) },
            )
        val address = varchar("address", 10485760)
        val deprecated = bool("deprecated")

        init {
            uniqueIndex(
                customIndexName = "deployed_smart_contract_name_chain",
                columns = arrayOf(name, chain),
                filterCondition = {
                    deprecated.eq(false)
                },
            )
        }
    }

    override fun run() {
        transaction {
            exec("CREATE TYPE Chain AS ENUM (${enumDeclaration<V1_Chain>()})")

            SchemaUtils.createMissingTablesAndColumns(V1_DeployedSmartContract)
        }
    }
}
