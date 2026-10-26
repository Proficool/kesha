// Модуль приложения «Кеша».
//
// Осознанные ограничения (см. KESHA-APK-SPEC.md, раздел 4):
//   * ни одной сторонней зависимости — только kotlin-stdlib, который и так нужен Kotlin;
//   * интерфейс строится кодом, XML-разметки нет;
//   * распознавание и синтез речи — системные, в APK не кладём ничего.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "by.colesa.kesha"
    compileSdk = 35

    defaultConfig {
        applicationId = "by.colesa.kesha"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        // Первый APK — намеренно debug: GitHub Actions собирает его без всякой настройки
        // подписи, и он же ставится на телефон «как есть».
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        // Release появится, когда приложение уже заработает: тогда сюда добавятся
        // minify + keystore из Secrets.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Выкидываем служебные проверки Kotlin — заметно уменьшает dex.
        freeCompilerArgs += listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }

    buildFeatures {
        buildConfig = false
        aidl = false
        renderScript = false
        shaders = false
    }

    // Метаданные о зависимостях в APK не нужны (экономит ~3 КБ).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "kotlin-tooling-metadata.json",
                // META-INF/com/android/build/gradle/app-metadata.properties (56 байт)
                // сюда добавить нельзя: AGP дописывает его в APK уже после упаковки
                // ресурсов, и apksigner из-за него выдаёт безобидное предупреждение.
                "DebugProbesKt.bin",
                "**/*.proto",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Единственная зависимость — стандартная библиотека Kotlin (её подключает сам плагин).
dependencies {
}
