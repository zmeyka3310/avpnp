// Note: this environment cannot reach repo1.maven.org or plugins.gradle.org. We resolve Maven
// Central through Google's official mirror instead. Remove the mirror entries if your network
// reaches Maven Central directly.
pluginManagement {
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "avpnp"
include(":app")
