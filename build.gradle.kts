import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "icu.nullptr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
}

kotlin {
    targets {
        jvm()
        js(IR) {
            browser()
            binaries.executable()
        }
    }

    @Suppress("UNUSED_VARIABLE")
    sourceSets {
        val telegramBotApi = "3.1.0"
        val ktorVersion = "2.0.3"

        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0-RC")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation("dev.inmo:tgbotapi.webapps:$telegramBotApi")
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.7.2")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("com.gitlab.AuroraOSS:gplayapi:0e224071")
                implementation("dev.inmo:tgbotapi:$telegramBotApi")
                implementation("dev.inmo:micro_utils.ktor.server:0.12.1")
                implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")
                implementation("io.ktor:ktor-client-okhttp-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
                runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:2.18.0")
                runtimeOnly("org.apache.logging.log4j:log4j-core:2.18.0")
                runtimeOnly("org.apache.logging.log4j:log4j-api:2.18.0")
            }
        }
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
    kotlinOptions.freeCompilerArgs = listOf("-Xcontext-receivers")
}

tasks.withType<Jar> {
    manifest.attributes("Main-Class" to "MainKt")
}
