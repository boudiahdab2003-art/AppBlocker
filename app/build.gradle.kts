import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasKeystore) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.appblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.appblocker"
        minSdk = 24
        targetSdk = 35 // Google Play requires 35+ for new app submissions
        versionCode = 92
        versionName = "1.91"

        // AI Coach server proxy (docs/SERVER.md #1). When both are non-empty the coach routes
        // requests through our VM (which holds the Gemini key) instead of needing an on-device
        // key. Empty = proxy off (the app falls back to the user's own key, unchanged behaviour).
        // Filled from gradle properties (root gradle.properties) so they're easy to rotate.
        buildConfigField("String", "COACH_PROXY_URL",
            "\"${providers.gradleProperty("coachProxyUrl").getOrElse("")}\"")
        buildConfigField("String", "COACH_PROXY_SECRET",
            "\"${providers.gradleProperty("coachProxySecret").getOrElse("")}\"")
    }

    // Two distribution channels, same app: "github" = the original sideloaded build with the
    // self-updater and all schedule types; "play" = Google Play build (Play forbids self-updating
    // APKs, and background location would trigger the heaviest review — see src/*/Dist.kt).
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            isDefault = true
        }
        create("play") {
            dimension = "dist"
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true // for the AI Coach proxy fields above
    }
    lint {
        // Skip the slow lintVital pass on every release build — this is a personal app
        // published from a known-good branch, not a library where lint gates matter.
        checkReleaseBuilds = false
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Export Room schemas so future DB migrations can be authored and tested.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
