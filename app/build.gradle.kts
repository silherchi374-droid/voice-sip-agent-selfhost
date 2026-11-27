plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sparkcyf.voicesipagent.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.sparkcyf.voicesipagent"
        minSdk = 30
        targetSdk = 36
        versionCode = 104
        versionName = "0.2.1-selfhost1"
        // Filter all transitive JNI libraries as well as PJSIP's own module outputs.
        ndk {
            //abiFilters += listOf("arm64-v8a", "x86_64")
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing comes from ~/.gradle/gradle.properties (never from the repo); without it the
    // release APK is built unsigned.
    val signingProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("vsa.signing.$it").orNull }
    val releaseSigning = if (signingProps.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(signingProps.getValue("storeFile")!!)
            storePassword = signingProps["storePassword"]
            keyAlias = signingProps["keyAlias"]
            keyPassword = signingProps["keyPassword"]
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Unit tests run on the JVM against android.jar stubs: any real Android
    // framework call throws "Stub!". Return default values instead, so the
    // mocked-Android tests can run.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
}

dependencies {
    implementation(project(":voice-api"))
    implementation(project(":sip"))
    implementation(project(":gateway"))
    implementation(project(":telephony"))
    implementation(project(":privileged"))
    // Declared by this module's manifest; keep it on the app compile classpath for lint.
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    // Email notifications (missed calls, SMS/OTP, IP changes) via the user's own SMTP
    // account (e.g. Gmail + app password). Android build of JavaMail; no other mail code.
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
