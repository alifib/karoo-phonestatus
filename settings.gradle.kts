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
        // Provide credentials via gradle.properties (gpr.user / gpr.token) or
        // the USERNAME / TOKEN environment variables. See README.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("USERNAME")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("TOKEN")
            }
        }
    }
}

rootProject.name = "karoo-phonestatus"
include(":app")
