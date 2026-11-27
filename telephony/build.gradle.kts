plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sparkcyf.voicesipagent.telephony"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Unit tests run on the JVM against android.jar stubs: any real Android
    // framework call (e.g. `object : Call.Callback()`) throws "Stub!". Return
    // default values instead, so the mocked-Android tests can run.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":voice-api"))
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
