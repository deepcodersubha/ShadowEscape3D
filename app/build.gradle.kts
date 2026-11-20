plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.shadowescape"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.shadowescape3d"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Google Filament 3D PBR Engine & glTF/GLB Loader
    implementation("com.google.android.filament:filament-android:1.53.4")
    implementation("com.google.android.filament:gltfio-android:1.53.4")
    implementation("com.google.android.filament:filament-utils-android:1.53.4")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}