import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

fun localProp(key: String): String = localProperties.getProperty(key, "")

android {
    namespace = "com.example.ami"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ami"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GEMINI_API_KEY", "\"${localProp("GEMINI_API_KEY")}\"")
        buildConfigField("String", "AMI_BACKEND_BASE_URL", "\"${localProp("AMI_BACKEND_BASE_URL")}\"")
        buildConfigField("String", "AMI_ESCALATION_SHARED_SECRET", "\"${localProp("AMI_ESCALATION_SHARED_SECRET")}\"")
    }

    // Release signing is driven by local.properties so the keystore password never
    // reaches version control. Absent those keys the release build stays unsigned.
    val releaseStoreFile = localProp("AMI_RELEASE_STORE_FILE")
    val hasReleaseSigning = releaseStoreFile.isNotEmpty() && rootProject.file(releaseStoreFile).exists()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProp("AMI_RELEASE_STORE_PASSWORD")
                keyAlias = localProp("AMI_RELEASE_KEY_ALIAS")
                keyPassword = localProp("AMI_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // android.util.Log throws "not mocked" by default, which would make every
            // class that logs untestable on the JVM - including CaretakerAgent, whose
            // model-unavailable path logs a warning and is exactly the path worth testing.
            isReturnDefaultValues = true
        }
    }

    // MigrationTestHelper loads the exported schema JSON from the test APK's assets.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}

/**
 * Room 2.8.5's MigrationTestHelper reads the exported schema JSON with kotlinx-serialization
 * 1.8.x, but something on the classpath pins serialization to a strict 1.7.3 and silently
 * downgrades it. The mismatch only shows up at runtime, as an AbstractMethodError on
 * GeneratedSerializer, which fails every migration test with a stack trace that points at
 * neither the schema nor the migration.
 *
 * Forced rather than declared, because a plain dependency cannot outrank a `strictly`
 * constraint. Applied to every configuration rather than just the test ones: an
 * instrumentation test APK resolves shared classes out of the installed app APK, so
 * forcing this on the test classpath alone changes nothing at all.
 *
 * 1.8.1 is the version room-runtime 2.8.5 asks for in its own metadata before the pin
 * downgrades it, so this restores the intended graph rather than inventing a new one.
 */
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
        force("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
}