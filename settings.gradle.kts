import java.util.Properties

fun parseBooleanProperty(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
}

fun readUseMedia3SourceFlag(): Boolean {
    val mergedLocalProps = Properties().apply {
        listOf("local.properties", "local.dev.properties").forEach { fileName ->
            val file = file(fileName)
            if (file.exists()) {
                file.inputStream().use { load(it) }
            }
        }
    }
    val fromGradleProperty = providers.gradleProperty("USE_MEDIA3_SOURCE").orNull
    val fromLocalProperties = mergedLocalProps.getProperty("USE_MEDIA3_SOURCE")
    return parseBooleanProperty(fromGradleProperty ?: fromLocalProperties)
}

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
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

if (readUseMedia3SourceFlag()) {
    includeBuild("media") {
        dependencySubstitution {
            substitute(module("androidx.media3:media3-common")).using(project(":lib-common"))
            substitute(module("androidx.media3:media3-container")).using(project(":lib-container"))
            substitute(module("androidx.media3:media3-session")).using(project(":lib-session"))
            substitute(module("androidx.media3:media3-exoplayer")).using(project(":lib-exoplayer"))
            substitute(module("androidx.media3:media3-exoplayer-dash")).using(project(":lib-exoplayer-dash"))
            substitute(module("androidx.media3:media3-exoplayer-hls")).using(project(":lib-exoplayer-hls"))
            substitute(module("androidx.media3:media3-exoplayer-rtsp")).using(project(":lib-exoplayer-rtsp"))
            substitute(module("androidx.media3:media3-exoplayer-smoothstreaming")).using(project(":lib-exoplayer-smoothstreaming"))
            substitute(module("androidx.media3:media3-datasource")).using(project(":lib-datasource"))
            substitute(module("androidx.media3:media3-datasource-okhttp")).using(project(":lib-datasource-okhttp"))
            substitute(module("androidx.media3:media3-decoder")).using(project(":lib-decoder"))
            substitute(module("androidx.media3:media3-extractor")).using(project(":lib-extractor"))
            substitute(module("androidx.media3:media3-ui")).using(project(":lib-ui"))
        }
    }
}

rootProject.name = "My Application"
include(":app")
// include(":benchmark")  // TODO: create when ready
 
