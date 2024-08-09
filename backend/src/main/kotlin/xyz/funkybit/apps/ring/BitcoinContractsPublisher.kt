package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.web3j.crypto.Keys
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.db.DeployedSmartContractEntity

object BitcoinContractsPublisher {
    val logger = KotlinLogging.logger {}

    fun updateContracts() {
        transaction {
            ContractType.entries.forEach { contractType ->
                val deployedContract = DeployedSmartContractEntity
                    .findLastDeployedContractByNameAndChain(contractType.name, BitcoinClient.chainId)

                if (deployedContract == null) {
                    logger.info { "Deploying arch contract: $contractType" }
                    deployContract(contractType)
                } else if (deployedContract.deprecated) {
                    logger.info { "Upgrading arch contract: $contractType" }
                    deployContract(contractType)
                }
            }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun deployContract(contractType: ContractType) {
        javaClass.getResource("/program.elf")?.readBytes()?.let { elf ->
            ArchNetworkClient.deployProgram(elf.toUByteArray()).also {
                DeployedSmartContractEntity.create(
                    name = contractType.name,
                    chainId = BitcoinClient.chainId,
                    implementationAddress = Address(Keys.toChecksumAddress(it)),
                    proxyAddress = Address(Keys.toChecksumAddress(it)),
                    version = 1,
                )
            }
        }
    }
}
