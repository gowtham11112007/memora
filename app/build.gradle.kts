import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pulse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pulse.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        // The key lives in secrets.properties, which Git ignores, so it's in the APK but never in the repo.
        val secrets = Properties()
        rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { secrets.load(it) }
        buildConfigField("String", "AI_KEY", "\"${secrets.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "GROQ_KEY", "\"${secrets.getProperty("GROQ_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Not debuggable, so it runs much faster. The same key lets it update over the debug build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The APK takes the app's name, so people know what they're installing.
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "Memora.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // PNGs are only written with: ./gradlew testDebugUnitTest -Pshots
                // Calls the real AI services. Only runs with: ./gradlew testDebugUnitTest -Plive
                if (project.hasProperty("live")) {
                    test.systemProperty("memora.live", "true")
                    test.outputs.upToDateWhen { false }
                }
                if (project.hasProperty("shots")) {
                    test.systemProperty("roborazzi.test.record", "true")
                    test.outputs.upToDateWhen { false }
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Runs reminders at the right time, even if the app was closed or the phone restarted.
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    // Test-only, not in the APK. They run Android code on the JVM and render screens to PNG.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
