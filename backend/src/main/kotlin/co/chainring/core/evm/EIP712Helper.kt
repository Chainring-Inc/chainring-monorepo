package co.chainring.core.evm

import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.web3j.crypto.StructuredDataEncoder

object EIP712Helper {
    val logger = KotlinLogging.logger {}

    fun computeHash(tx: EIP712Transaction, chainId: ChainId, verifyingContract: Address): ByteArray {
        //
        // Structured data contains both the schema and the data, The type section holds the schema. The field ordering
        // is important since that is the order in which the data in these fields is appended before being hashed
        // for signing.
        //
        val encoder = StructuredDataEncoder(
            """
                {
                  "types": {
                    "EIP712Domain": [
                      {"name": "name", "type": "string"},
                      {"name": "version", "type": "string"},
                      {"name": "chainId", "type": "uint256"},
                      {"name": "verifyingContract", "type": "address"}
                    ],
                    "${tx.getTransactionType().name}": ${tx.getModel()}
                  },
                  "primaryType": "${tx.getTransactionType().name}",
                  "domain": {
                    "name": "ChainRing Labs",
                    "version": "0.0.1",
                    "chainId": ${chainId.value},
                    "verifyingContract": "${verifyingContract.value}"
                  },
                  "message": ${tx.getMessage()}
                }
            """.trimIndent(),
        )
        return encoder.hashStructuredData()
    }
}