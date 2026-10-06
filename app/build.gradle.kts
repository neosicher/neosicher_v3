plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.neosicher.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neosicher.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
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
        // Fuerza UTF-8 explícito para javac (en Windows, sin esto puede usar
        // el charset por defecto del sistema, p. ej. Windows-1252, y
        // malinterpretar fuentes con acentos/caracteres especiales).
        encoding = "UTF-8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // CameraX — cámara real del teléfono
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Permisos en Compose
    implementation(libs.accompanist.permissions)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // ML Kit Pose Detection — estimación de postura del bebé (on-device).
    implementation(libs.mlkit.pose.detection)

    // ML Kit Face Detection — v2: detectar si la cara está visible/girada/tapada.
    implementation(libs.mlkit.face.detection)

    debugImplementation(libs.androidx.ui.tooling)
}
