import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "xyz.funkybit"
version = "1.0"

repositories {
    mavenCentral()
}

plugins {
    kotlin("jvm") version "1.9.23"
    application
}

application {
    mainClass.set("xyz.funkybit.LocalMainKt")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val log4j2Version = "2.23.1"
val exposedVersion = "0.48.0"
val grpcKotlinStubVersion = "1.4.1"
val grpcProtobufVersion = "1.62.2"
val protobufKotlinVersion = "4.26.0"

dependencies {
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4j2Version")

    implementation("org.awaitility:awaitility-kotlin:4.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.postgresql:postgresql:42.3.9")
    implementation("org.apache.commons:commons-dbcp2:2.12.0")

    // Blockchain client
    implementation("org.web3j:core:4.10.3") // 4.11 introduces dependency (tech.pegasys:jc-kzg-4844) that is published to cloudsmith repository (https://github.com/web3j/web3j/issues/2013)

    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinStubVersion")
    implementation("io.grpc:grpc-protobuf:$grpcProtobufVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufKotlinVersion")
    implementation("io.grpc:grpc-netty:$grpcProtobufVersion")

    implementation(project(":backend"))
    implementation(project(":sequencercommon"))
    implementation(project(":sequencer"))
}

allprojects {
    apply(plugin = "base")
}

val compileContractsAndGenerateWrappers by tasks.register("compileContractsAndGenerateWrappers") {
    data class Contract(
        val nameInBuildDir: String,
        val generateJavaWrapper: Boolean,
        val generateTypescriptAbi: Boolean,
        val generateJavaTestWrapper: Boolean = false,
        val generateJavaWrapperForFixtures: Boolean = false,
    ) {
        val typeScriptAbiName = "${File(nameInBuildDir).nameWithoutExtension}Abi"
    }

    val contracts = listOf(
        Contract(
            nameInBuildDir = "Exchange.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = true
        ),
        Contract(
            nameInBuildDir = "ERC1967Proxy.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = false
        ),
        Contract(
            nameInBuildDir = "UUPSUpgradeable.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = false
        ),
        Contract(
            nameInBuildDir = "ERC20.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = true
        ),
        Contract(
            nameInBuildDir = "MockERC20.sol",
            generateJavaWrapper = true,
            generateTypescriptAbi = false,
            generateJavaTestWrapper = true,
            generateJavaWrapperForFixtures = true
        ),
    )

    println("Building Solidity contracts")
    exec {
        isIgnoreExitValue = true
        commandLine(
            "bash", "-c", "forge build --root ${projectDir}/contracts --out ${buildDir}/contracts"
        )
    }.also {
        if (it.exitValue == 127) {
            throw Exception("Foundry is missing, please follow the instructions: https://book.getfoundry.sh/getting-started/installation")
        }
    }

    val javaBackendWrappersOutputDir = File("${projectDir}/backend/src/main/java")
    val javaTestWrappersOutputDir = File("${projectDir}/integrationtests/src/main/java")
    val javaWrappersOutputDir = File("${projectDir}/src/main/java")
    val typeScriptAbiOutputDir = File("${projectDir}/web-ui/src/contracts")

    contracts.forEach { contract ->
        val dirFile = File("${buildDir}/contracts/${contract.nameInBuildDir}")
        val jsonFile = File("${dirFile.absolutePath}/${dirFile.nameWithoutExtension}.json")

        if (!dirFile.exists()) {
            throw Exception("File not found ${dirFile.absolutePath}")
        }

        if (!jsonFile.exists()) {
            throw Exception("File not found ${jsonFile.absolutePath}")
        }

        val json = JsonSlurper().parseText(jsonFile.readText()) as Map<*, *>
        val abiJson = JsonOutput.prettyPrint(JsonOutput.toJson(json["abi"] as ArrayList<*>))

        val abiFile = File(jsonFile.absolutePath.replace(".json", ".abi")).also {
            it.writeText(abiJson)
        }

        val binFile = File(jsonFile.absolutePath.replace(".json", ".bin")).also {
            val byteCode = JsonOutput.toJson((json["bytecode"] as Map<*, *>)["object"])!!
            it.writeText(byteCode.replace("0x", "").replace("\"", ""))
        }

        if (contract.generateJavaWrapper) {
            exec {
                isIgnoreExitValue = true
                commandLine(
                    "bash",
                    "-c",
                    "web3j generate solidity -b ${binFile.absolutePath} -a ${abiFile.absolutePath} -o ${javaBackendWrappersOutputDir.absolutePath} -p xyz.funkybit.contracts.generated"
                )
            }.also {
                if (it.exitValue == 127) {
                    throw Exception("Web3j is missing, please run 'brew tap web3j/web3j && brew install web3j' to install")
                }
            }
        }

        if (contract.generateJavaTestWrapper) {
            exec {
                isIgnoreExitValue = true
                commandLine(
                    "bash",
                    "-c",
                    "web3j generate solidity -b ${binFile.absolutePath} -a ${abiFile.absolutePath} -o ${javaTestWrappersOutputDir.absolutePath} -p xyz.funkybit.contracts.generated"
                )
            }.also {
                if (it.exitValue == 127) {
                    throw Exception("Web3j is missing, please run 'brew tap web3j/web3j && brew install web3j' to install")
                }
            }
        }

        if (contract.generateJavaWrapperForFixtures) {
            exec {
                isIgnoreExitValue = true
                commandLine(
                    "bash",
                    "-c",
                    "web3j generate solidity -b ${binFile.absolutePath} -a ${abiFile.absolutePath} -o ${javaWrappersOutputDir.absolutePath} -p xyz.funkybit.contracts.generated"
                )
            }.also {
                if (it.exitValue == 127) {
                    throw Exception("Web3j is missing, please run 'brew tap web3j/web3j && brew install web3j' to install")
                }
            }
        }

        if (contract.generateTypescriptAbi) {
            File("${typeScriptAbiOutputDir.absolutePath}/${contract.typeScriptAbiName}.ts").also {
                print("Generating TypeScript ABI for ${contract.nameInBuildDir} ...")
                it.writeText("export default $abiJson as const")
                println("File written to ${it.absolutePath}")
            }
        }
    }

    // generate web-ui/contracts/index.ts
    contracts
        .filter { it.generateTypescriptAbi }
        .map { it.typeScriptAbiName }
        .also {
            if (it.isNotEmpty()) {
                File("${typeScriptAbiOutputDir.absolutePath}/index.ts")
                    .writeText(it.joinToString("\n") { abiName -> "import $abiName from 'contracts/${abiName}'" } + "\n" + "export { ${it.joinToString(", ")} }\n")
            }
        }

    println("Web3j contract wrappers and Typescript ABIs generated successfully for all EVM contracts.")

    println("Building Arch contracts")
    val elfFile = File("${projectDir}/contracts/arch/contracts/exchange/target/program.elf")

    exec {
        isIgnoreExitValue = true
        commandLine(
            "bash", "-c", "cd ${projectDir}/contracts/arch/contracts/exchange && cargo build && cp $elfFile ${projectDir}/backend/src/main/resources"
        )
    }.also {
        if (it.exitValue == 127) {
            throw Exception("Cargo is missing, please follow the instructions: https://doc.rust-lang.org/cargo/getting-started/installation.html")
        }
    }

    println("Arch contract successfully processed")
}
