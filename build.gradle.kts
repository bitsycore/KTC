plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = "com.bitsycore"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("com.bitsycore.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.bitsycore.MainKt"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from({
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }
    })

    // Re-run whenever a resource file changes (e.g. stdlib/*.kt)
    inputs.dir("src/main/resources")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}