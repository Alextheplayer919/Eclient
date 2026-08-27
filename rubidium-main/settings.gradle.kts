
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.android") version "2.0.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://repo.opencollab.dev/maven-snapshots/")
        }
        maven {
            url = uri("https://repo.opencollab.dev/maven-releases/")
        }
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "NexoraClient"
include(":app")

// ── CloudburstMC/Protocol kaynağını Maven SNAPSHOT yerine doğrudan
// kaynaktan derliyoruz — repo.opencollab.dev'deki yayınlanmış
// 3.0.0.Beta6-SNAPSHOT, upstream'de v818+ commit'leri olduğu halde
// hâlâ v800'de (1.21.80) takılı kalmış (CI/publish pipeline gecikmesi).
// build.yml CI içinde "relay/Protocol" klasörüne kaynağı clone ediyor;
// bu blok da app/build.gradle.kts'teki mevcut
//   implementation("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta6-SNAPSHOT")
// satırlarına HİÇ DOKUNMADAN, bu bağımlılıkları otomatik olarak
// yerel kaynaktan derlenmiş haliyle değiştiriyor.
if (file("relay/Protocol/settings.gradle.kts").exists()) {
    includeBuild("relay/Protocol") {
        dependencySubstitution {
            substitute(module("org.cloudburstmc.protocol:bedrock-codec")).using(project(":bedrock-codec"))
            substitute(module("org.cloudburstmc.protocol:bedrock-connection")).using(project(":bedrock-connection"))
            substitute(module("org.cloudburstmc.protocol:common")).using(project(":common"))
        }
    }
}
