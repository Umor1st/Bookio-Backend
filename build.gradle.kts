plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "ru.bookio"
version = "1.0.0-SNAPSHOT"

application { mainClass = "ru.bookio.bootstrap.MainKt" }
kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.ktor.server.core)
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.netty)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)

    constraints {
        testImplementation("commons-codec:commons-codec:1.23.0") {
            because("Fixes CVE-2026-119078 pulled in transitively by Testcontainers")
        }
    }
}

tasks.test { useJUnitPlatform() }
