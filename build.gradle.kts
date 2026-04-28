plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    application
}

group = "eagle"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP client + WebSocket
    implementation("io.ktor:ktor-client-core:2.3.10")
    implementation("io.ktor:ktor-client-cio:2.3.10")
    implementation("io.ktor:ktor-client-websockets:2.3.10")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.10")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.10")

    // Local web dashboard (web mode)
    implementation("io.ktor:ktor-server-core:2.3.10")
    implementation("io.ktor:ktor-server-cio:2.3.10")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.10")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // .env loading
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

application {
    mainClass.set("eagle.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "eagle.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
