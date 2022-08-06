import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "icu.nullptr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.gitlab.AuroraOSS:gplayapi:0e224071")
    implementation("dev.inmo:tgbotapi:2.2.1")
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")
    implementation("io.ktor:ktor-client-okhttp-jvm:2.0.3")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:2.18.0")
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.18.0")
    runtimeOnly("org.apache.logging.log4j:log4j-api:2.18.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
    kotlinOptions.freeCompilerArgs = listOf("-Xcontext-receivers")
}

tasks.jar {
    manifest.attributes("Main-Class" to "MainKt")
}
