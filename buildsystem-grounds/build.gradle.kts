import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

applyCoreConfiguration()

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("de.eldoria.plugin-yml.bukkit") version "0.9.0"
}

project.description = "Grounds map registry integration"

repositories {
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Only the published API, never buildsystem-core. This module is a separate plugin that
    // talks to BuildSystem the way any third party would, which is what keeps an upstream
    // merge from ever touching Grounds code — and Grounds code from ever holding upstream back.
    compileOnly(project(":buildsystem-api"))
    compileOnly(libs.paperapi)
    compileOnlyApi(libs.jspecify)

    // The bundle format the registry addresses by digest: `bundle/sha256/<ab>/<sha>.tar.zst`.
    // Region files compress far better under zstd than deflate, and the key already names it.
    implementation(libs.commons.compress)
    implementation(libs.zstd)

    // The server API is compileOnly, so its Gson is absent at test runtime. The MockBukkit-matched
    // version, matching buildsystem-core — see libs.versions.toml.
    testImplementation(libs.papertest)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> { useJUnitPlatform() }

// plugin.yml is generated, the same way buildsystem-core generates its own — a hand-written
// one with a ${version} placeholder needs resource filtering, which the configuration cache
// refuses.
bukkit {
    name = "GroundsMaps"
    version = "${project.version}"
    description = "Publishes build worlds to the Grounds map registry"
    author = "Grounds"

    main = "gg.grounds.buildsystem.GroundsMapsPlugin"
    apiVersion = "26.1"
    // Hard: every command reads a BuildWorld, so without BuildSystem there is nothing this
    // plugin could do except fail once per command.
    depend = listOf("BuildSystem")

    commands {
        register("map") {
            description = "Publish, fork, pull and inspect maps on the build server"
            usage = "/<command> [login|logout|status|push|pull|fork|versions|link|poi|setup]"
            permission = "grounds.map"
        }
        register("ms") {
            description = "Mark a place while setting a map up"
            usage = "/<command> <red|blue|…> <spawn|bed|iron|…>"
            permission = "grounds.map"
        }
    }

    permissions {
        register("grounds.map") {
            description = "Use the map commands on the build server"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
        register("grounds.maps.pull") {
            description = "Pull a published map onto the build server"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
        register("grounds.maps.pull.force") {
            description = "Overwrite an existing world with /map pull -f"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
    }
}

tasks.named("assemble") { dependsOn(tasks.named("shadowJar")) }

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("GroundsMaps-${project.version}.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))

    // commons-compress is relocated because a build server runs other plugins and two copies on
    // one classpath is a class-loading argument nobody wins.
    //
    // zstd-jni is NOT, and must not be: it is a JNI library whose native code registers its
    // methods under the original package name. Renaming the Java classes leaves those natives
    // unfindable, and the failure arrives as UnsatisfiedLinkError the first time a world is
    // packed — long after the build looked fine.
    val shadePath = "gg.grounds.buildsystem.external"
    relocate("org.apache.commons.compress", "$shadePath.compress")

    // A relocation of zstd builds cleanly and only fails when a builder packs their first world,
    // which is the worst possible moment to find out. Check the jar instead.
    doLast {
        val jar = archiveFile.get().asFile
        val intact = ZipFile(jar).use { zip ->
            zip.stream().anyMatch { it.name.startsWith("com/github/luben/zstd/") }
        }
        check(intact) {
            "zstd-jni is missing or relocated in $jar. Its native code registers methods under the" +
                " original package name, so renaming the classes leaves them unfindable."
        }
    }
}
