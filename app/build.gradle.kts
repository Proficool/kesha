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
        versionCode = 9
        versionName = "1.8"
    }

    /**
     * Постоянный ключ для отладочных сборок, лежит в репозитории.
     *
     * Зачем: раннер GitHub Actions одноразовый, и Gradle при каждом запуске создавал
     * НОВЫЙ отладочный ключ в ~/.android/debug.keystore. Из-за этого два соседних
     * билда из CI были подписаны по-разному, и новый APK нельзя было поставить поверх
     * предыдущего — Android отказывал по несовпадению подписи. С постоянным ключом
     * любая сборка (и CI, и локальная) ставится поверх любой другой.
     *
     * ВАЖНО: этот ключ публичный, он лежит в открытом репозитории. Он годится только
     * для отладки и установки «с рук». Для Google Play нужен отдельный release-ключ,
     * которого в репозитории быть не должно.
     */
    signingConfigs {
        create("keshaDebug") {
            storeFile = rootProject.file("keystore/kesha-debug.p12")
            storePassword = "kesha-debug"
            keyAlias = "kesha"
            keyPassword = "kesha-debug"
        }
    }

    buildTypes {
        // Первый APK — намеренно debug: он ставится на телефон «как есть».
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("keshaDebug")
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
