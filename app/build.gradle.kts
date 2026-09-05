import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sackup"
    compileSdk = 35

    signingConfigs {
        val keystoreFile = rootProject.file("sackup-release.jks")
        if (keystoreFile.exists()) {
            val props = Properties().apply {
                rootProject.file("local.properties").takeIf { it.exists() }
                    ?.reader()?.use { load(it) }
            }
            // Gradle properties (-Psigning.x=... or ~/.gradle/gradle.properties)
            // win over local.properties so CI can inject secrets.
            fun signingProp(name: String): String =
                project.findProperty(name)?.toString() ?: props.getProperty(name, "")
            create("release") {
                storeFile = keystoreFile
                storePassword = signingProp("signing.storePassword")
                keyAlias = signingProp("signing.keyAlias")
                keyPassword = signingProp("signing.keyPassword")
            }
        } else {
            logger.warn(
                "SackUp: release keystore ${keystoreFile.name} not found — " +
                    "builds will be signed with the debug keystore."
            )
        }
    }

    defaultConfig {
        applicationId = "com.sackup"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        debug {
            // Use the release keystore when present (local builds); otherwise
            // fall back to the auto-generated debug keystore so CI produces a
            // signed, installable app-debug.apk instead of an unsigned one.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Lets data classes that merely carry android.net.Uri load in plain JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this
            variant.assembleProvider.get().doLast {
                val src = output.outputFile
                val dest = rootProject.file("release/${src.name}")
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
                println("Copied ${src.name} → release/")
            }
        }
    }
}

ksp {
    // Export the Room schema so migrations can be reviewed and tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DocumentFile for SAF
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Gson for simple JSON serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // Coil for image loading/thumbnails
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Unit tests (plain JVM, run with ./gradlew testDebugUnitTest)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
}
