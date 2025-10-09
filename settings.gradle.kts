rootProject.name = "codex_to_claude"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("external/kotlin-sdk") {
    dependencySubstitution {
        substitute(module("io.modelcontextprotocol:kotlin-sdk")).using(project(":kotlin-sdk"))
        substitute(module("io.modelcontextprotocol:kotlin-sdk-server")).using(project(":kotlin-sdk-server"))
        substitute(module("io.modelcontextprotocol:kotlin-sdk-client")).using(project(":kotlin-sdk-client"))
        substitute(module("io.modelcontextprotocol:kotlin-sdk-core")).using(project(":kotlin-sdk-core"))
    }
}
