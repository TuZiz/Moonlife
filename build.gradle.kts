plugins {
    kotlin("jvm") version "2.4.0-Beta1"
    id("com.gradleup.shadow") version "9.4.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("me.clip:placeholderapi:2.11.6")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("net.kyori", "ym.moonlife.libs.kyori")
    }

    processResources {
        val props = mapOf("version" to project.version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
