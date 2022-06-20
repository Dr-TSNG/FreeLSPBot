import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.0"
    kotlin("plugin.serialization") version "1.7.0"
}

group = "icu.nullptr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.inmo:tgbotapi:2.0.3")
    implementation("io.github.microutils:kotlin-logging-jvm:2.1.23")
    implementation("io.ktor:ktor-client-okhttp:2.0.2")
    runtimeOnly("org.slf4j:slf4j-log4j12:1.7.36")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.jar {
    manifest.attributes("Main-Class" to "MainKt")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    configurations.compileClasspath.get().forEach {
        from(if (it.isDirectory) it else zipTree(it))
    }
}
