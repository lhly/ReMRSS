import java.util.Properties

plugins {
    id("com.android.library")
}

val sdkVersionProperties = Properties().apply {
    file("version.properties").inputStream().use { load(it) }
}
val sdkVersion = requireNotNull(sdkVersionProperties.getProperty("sdkVersion")) {
    "sdk/android/version.properties must define sdkVersion"
}

android {
    namespace = "dev.remrss.platform.sdk"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addStaticSourceDirectory(file("../../scene-geometry/src/main/kotlin").absolutePath)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val testHome = layout.buildDirectory.dir("unitTestHome").get().asFile
    systemProperty("user.home", testHome.absolutePath)
    doFirst { testHome.mkdirs() }
}
