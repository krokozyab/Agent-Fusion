
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("application")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.orchestrator"
version = "0.1.0"

repositories {
    mavenCentral()
}

val coroutinesVersion = "1.9.0"
val serializationVersion = "1.7.3"
val ktorVersion = "3.0.3"
val duckdbVersion = "1.4.0.0"
val mcpSdkVersion = "0.7.2"

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")

    // DuckDB JDBC
    implementation("org.duckdb:duckdb_jdbc:$duckdbVersion")

    // HOCON (Typesafe Config)
    implementation("com.typesafe:config:1.4.3")

    // MCP Kotlin SDK (bidirectional transport support)
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpSdkVersion")

    // TOML parser (ktoml)
    val ktomlVersion = "0.5.1"
    implementation("com.akuleshov7:ktoml-core:$ktomlVersion")
    implementation("com.akuleshov7:ktoml-file:$ktomlVersion")

    // ULID (time-sortable unique identifiers)
    implementation("io.azam.ulidj:ulidj:2.0.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Test
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.12")
}

application {
    mainClass.set("com.orchestrator.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
