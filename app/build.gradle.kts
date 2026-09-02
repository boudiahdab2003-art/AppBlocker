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
        versionCode = 152
        versionName = "1.151"

        // Runs the rendering tests in app/src/androidTest — the layer that catches what unit tests
        // structurally cannot: a button under the keyboard, a label clipped at a large system font,
        // a block-screen layout that composes to nothing. Every one of those shipped to the owner's
        // phone and was found by him, because nothing in the pipeline ever drew a screen.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AI Coach server proxy (docs/SERVER.md #1). When both are non-empty the coach routes
        // requests through our VM (which holds the Gemini key) instead of needing an on-device
        // key. Empty = proxy off (the app falls back to the user's own key, unchanged behaviour).
        // Filled from gradle properties (root gradle.properties) so they're easy to rotate.
        buildConfigField("String", "COACH_PROXY_URL",
            "\"${providers.gradleProperty("coachProxyUrl").getOrElse("")}\"")
        buildConfigField("String", "COACH_PROXY_SECRET",
            "\"${providers.gradleProperty("coachProxySecret").getOrElse("")}\"")

        // Bug reporting (docs/SERVER.md #2). Empty = reporting off, which is the default and what
        // every local build gets.
        //
        // Unlike the coach's, this secret is read from an ENVIRONMENT VARIABLE first and is
        // deliberately NOT committed: this repository is public, and coachProxySecret sitting in
        // gradle.properties is already readable by anyone. That was an accepted trade for a
        // Gemini key capped by its own quota; a token that can write to the owner's issue
        // tracker is not the same bet. The release workflow supplies REPORT_SECRET from a GitHub
        // Actions secret. The gradle-property fallback exists only so the owner can test on his
        // own PC without editing a tracked file.
        buildConfigField("String", "REPORT_URL",
            "\"${System.getenv("REPORT_URL") ?: providers.gradleProperty("reportUrl").getOrElse("")}\"")
        buildConfigField("String", "REPORT_SECRET",
            "\"${System.getenv("REPORT_SECRET") ?: providers.gradleProperty("reportSecret").getOrElse("")}\"")
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
    // MigrationTestHelper reads the exported schemas from the test APK's assets at runtime, so
    // schemas/ has to be shipped onto the device. Without this, MigrationTest fails with "Cannot
    // find the schema file in the assets folder" rather than with anything about the migration.
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
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
    // The real org.json, because unitTests.isReturnDefaultValues stubs Android's out to return
    // nulls — which would make BugReportTest's "no blocked word survives into the payload"
    // assertion pass against an empty string and prove nothing. A redaction test that cannot
    // fail is worse than no test.
    testImplementation("org.json:json:20240303")

    // Rendering tests (app/src/androidTest). These run on a device or emulator because the bugs
    // they exist for are measurement bugs: they only appear once something has actually been laid
    // out against a real width, height, density and font scale. A JVM test cannot have them.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    // GrantPermissionRule. Without it the notification tests assume-away in CI, where the app
    // is installed with no runtime permissions — green, and testing nothing.
    androidTestImplementation("androidx.test:rules:1.5.0")
    // Room's MigrationTestHelper. It reads the exported schemas off the device, which is
    // why the androidTest source set carries schemas/ as an asset directory above.
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    // Supplies the bare ComponentActivity that createComposeRule() hosts the composable in.
    // debugImplementation, not androidTestImplementation: it contributes a manifest entry, which
    // has to be merged into the app under test rather than into the test APK.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
