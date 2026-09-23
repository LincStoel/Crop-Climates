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

// Jade is optional at runtime - only its own plugin discovery ever loads
// compat.jade.CropClimatesJadePlugin - so it is compileOnly, like Cold Sweat.
val jadeJar = providers.gradleProperty("jadeJar")
    .orElse("libs/Jade-1.21.1-NeoForge-15.10.6.jar")

// Dev-only stress harness (see tools/stress/README.md): a second mod that the
// stress runs load alongside crop_climates. It is never part of the jar.
val stress: SourceSet = sourceSets.create("stress")

neoForge {
    version = "21.1.248"

    parchment {
        mappingsVersion = "2024.11.17"
        minecraftVersion = "1.21.1"
    }

    mods {
        create("crop_climates") {
            sourceSet(sourceSets.main.get())
        }
        create("crop_climates_stress") {
            sourceSet(stress)
        }
    }

    runs {
        create("client") {
            client()
            loadedMods = setOf(mods["crop_climates"])
        }
        create("server") {
            server()
            loadedMods = setOf(mods["crop_climates"])
        }
        create("stressServer") {
            server()
            gameDirectory = layout.projectDirectory.dir("run-stress/server")
            sourceSet = stress
            loadedMods = setOf(mods["crop_climates"], mods["crop_climates_stress"])
            programArgument("--nogui")
            jvmArguments.addAll("-Xms4G", "-Xmx8G")
        }
        create("stressClient") {
            client()
            gameDirectory = layout.projectDirectory.dir("run-stress/client")
            sourceSet = stress
            loadedMods = setOf(mods["crop_climates"], mods["crop_climates_stress"])
            programArguments.addAll("--quickPlayMultiplayer", "127.0.0.1:25565", "--username", "StressBot",
                    "--width", "1280", "--height", "720")
        }
    }

    addModdingDependenciesTo(stress)
}

dependencies {
    compileOnly(files(coldSweatJar.get()))
    compileOnly(files(jadeJar.get()))

    "stressImplementation"(sourceSets.main.get().output)
    "stressCompileOnly"(files(coldSweatJar.get()))

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
