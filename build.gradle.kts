plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = "com.bitsycore"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.bitsycore.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}