plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.orchestrator"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    val ktorVersion = "3.3.0"

    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Ktor Server (aligned with embedded MCP SDK)
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-id:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-default-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")

    // Database (upgrade to fix duplicate-key failures in DuckDB 0.9.x)
    implementation("org.duckdb:duckdb_jdbc:1.4.0.0")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    
    // Configuration
    implementation("com.typesafe:config:1.4.3")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("com.akuleshov7:ktoml-core:0.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // UUID
    implementation("app.softwork:kotlinx-uuid-core:0.0.22")
    implementation("io.azam.ulidj:ulidj:1.0.0")

    // MCP Kotlin SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.1.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-core:0.1.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.1.3")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.1.3")

    // Java Parser for chunking
    implementation("com.github.javaparser:javaparser-core:3.25.7")
    
    // YAML Parser for chunking
    implementation("org.yaml:snakeyaml:2.2")
    
    // ONNX Runtime for embeddings
    implementation("com.microsoft.onnxruntime:onnxruntime:1.16.3")

    // JGit for git history analysis
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.13.9")
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

tasks.shadowJar {
    archiveBaseName.set("orchestrator")
    archiveClassifier.set("all")
    mergeServiceFiles()
}
