import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform") version "1.7.22"
    kotlin("plugin.serialization") version "1.7.22"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/kotlinx-html/maven")
}

kotlin {
    targets {
        jvm {
            registerShadowJar("FreeLSPBot", "MainKt")
        }

        js(IR) {
            browser()
            binaries.executable()
        }
    }

    @Suppress("UNUSED_VARIABLE")
    sourceSets {
        val telegramBotApi = "4.2.0"
        val exposedVersion = "0.41.1"
        val ktorVersion = "2.1.3"

        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation("dev.inmo:tgbotapi.webapps:$telegramBotApi")
                implementation("io.ktor:ktor-websockets:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-html-js:0.7.2")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("dev.inmo:tgbotapi:$telegramBotApi")
                implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")
                implementation("io.ktor:ktor-client-okhttp-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-websockets:$ktorVersion")
                implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
                implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
                implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
                implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
                runtimeOnly("mysql:mysql-connector-java:8.0.30")
                runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:2.18.0")
                runtimeOnly("org.apache.logging.log4j:log4j-core:2.18.0")
                runtimeOnly("org.apache.logging.log4j:log4j-api:2.18.0")
            }
        }
    }
}

fun KotlinJvmTarget.registerShadowJar(archiveName: String?, mainClassName: String?) {
    val targetName = name
    compilations.named("main") {
        tasks {
            val shadowJar = register<ShadowJar>("${targetName}ShadowJar") {
                group = "build"
                from(output)
                configurations = listOf(runtimeDependencyFiles)
                archiveName?.let { archiveBaseName.set(archiveName) }
                archiveVersion.set("")
                mainClassName?.let { manifest.attributes("Main-Class" to it) }

                mergeServiceFiles()
            }

            getByName("${targetName}Jar") {
                finalizedBy(shadowJar)
            }
        }
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
    kotlinOptions.freeCompilerArgs = listOf("-Xcontext-receivers")
}
