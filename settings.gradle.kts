pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "zhique"
include(":app", ":core:common", ":core:project", ":core:paste", ":core:web",
        ":core:ai", ":core:agent", ":core:permission", ":core:export",
        ":core:publish", ":core:apilot", ":template-min", ":template-full")
