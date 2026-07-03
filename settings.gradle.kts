pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // karoo-ext is published to GitHub Packages. Even though the package is
        // public, GitHub Packages *always* requires authentication to read.
        // Only the token is validated; the username may be any non-empty string.
        // Provide the token via gradle.properties (gpr.token) or the TOKEN env
        // variable. See README.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("USERNAME")
                    ?: "token"
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("TOKEN")
            }
        }
    }
}

rootProject.name = "karoo-phonestatus"
include(":app")
