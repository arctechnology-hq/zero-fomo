// Top-level build file: plugin versions come from gradle/libs.versions.toml
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Keep ALL build output on plain local disk on developer Windows machines
// (OneDrive-synced checkouts de-materialize files in build/ mid-build:
// undeletable Hilt dirs, KSP outputs turned into cloud placeholders).
// CI and non-Windows hosts use the normal build/ layout. Override with
// ZEROFOMO_BUILD_DIR when needed.
val buildRedirect: String? = System.getenv("ZEROFOMO_BUILD_DIR")
    ?: if (System.getProperty("os.name").startsWith("Windows"))
        "${System.getProperty("user.home")}/android-dev/gradle-build/zero_fomo" else null
if (buildRedirect != null) {
    allprojects {
        layout.buildDirectory.set(File("$buildRedirect/${project.name}"))
    }
}
