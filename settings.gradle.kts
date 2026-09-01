import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "BuildSystem"

include("buildsystem-api")
include("buildsystem-core")
include("buildsystem-grounds")

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        maven {
            name = "Grounds GitHub Packages"
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username = providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
            content { includeGroup("gg.grounds") }
        }
        mavenCentral()
        maven {
            name = "Spigot"
            url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        }
        maven {
            name = "PaperMC"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
        maven {
            name = "OSS Sonatype Snapshots"
            url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        }
        maven {
            name = "AuthLib"
            url = uri("https://libraries.minecraft.net/")
        }
        maven {
            name = "EngineHub"
            url = uri("https://maven.enginehub.org/repo/")
        }
        maven {
            name = "PlaceholderAPI"
            url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        }
        maven {
            name = "Modrinth"
            url = uri("https://api.modrinth.com/maven")
            content { includeGroup("maven.modrinth") }
        }
        ivy("https://api.modrinth.com/maven/maven/modrinth/axiom-paper-plugin/5.0.4+26.1") {
            name = "Modrinth Maven Workaround for axiom-paper-plugin"
            patternLayout { artifact("AxiomPaperPlugin-5.0.4-for-MC26.1.jar") }
            metadataSources { artifact() }
            content { includeModule("maven.modrinth.workaround", "axiom-paper-plugin") }
        }
    }
}
