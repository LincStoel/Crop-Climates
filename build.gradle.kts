plugins {
    id("java")
    id("net.neoforged.moddev") version "2.0.143"
}

group = "com.worldtraveler.cropclimates"
version = "1.0.0"

base {
    archivesName = "crop_climates"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenCentral()
}

// Cold Sweat is not published on a public Maven, so it is compiled against
// directly from the jar. Override with -PcoldSweatJar=<path> or a
// gradle.properties entry; defaults to the copy documented there.
val coldSweatJar = providers.gradleProperty("coldSweatJar")
    .orElse("libs/ColdSweat-2.4.3.jar")

neoForge {
    version = "21.1.248"

    parchment {
        mappingsVersion = "2024.11.17"
        minecraftVersion = "1.21.1"
    }

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }

    mods {
        create("crop_climates") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    compileOnly(files(coldSweatJar.get()))

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// EnclosureFloodTest exercises EnclosureFlood against synthetic grids built
// from net.minecraft.core.BlockPos/Direction - plain data carriers, not live
// world state - so the test source set needs Minecraft on its classpath even
// though it never touches a Level. NeoGradle only wires that up for "main".
sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}

tasks.test {
    useJUnitPlatform()
}
