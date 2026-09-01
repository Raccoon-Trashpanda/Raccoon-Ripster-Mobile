import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Секреты подписи НЕ в репозитории. Положи их в local.properties (он в
// .gitignore) или в переменные окружения:
//   RIPSTER_RELEASE_STORE_FILE=ripster-release.jks
//   RIPSTER_RELEASE_STORE_PASSWORD=...
//   RIPSTER_RELEASE_KEY_ALIAS=ripster
//   RIPSTER_RELEASE_KEY_PASSWORD=...
// Без них release-сборка просто выйдет неподписанной — debug работает всегда.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String? =
    localProps.getProperty(key) ?: System.getenv(key)

android {
    namespace = "net.ripster.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.ripster.mobile"
        // minSdk 26 намеренно: у владельца ДВЕ цели для проверки, и они разные —
        // BlueStacks Pie64 это Android 9 (API 28), а готовый AVD `wvd` — API 30.
        // 26 влезает в обе и не тянет за собой поддержку доисторических версий.
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.12"

        // Нативный аудиодвижок (фаза 1): Oboe + FLAC/WAV декод. x86_64 — для
        // эмулятора; arm — для реальных устройств. armeabi-v7a пока не тащим.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Oboe (prefab) требует shared STL.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "26.3.11579264"

    signingConfigs {
        create("release") {
            val storeF = secret("RIPSTER_RELEASE_STORE_FILE")
            if (storeF != null && rootProject.file(storeF).exists()) {
                storeFile = rootProject.file(storeF)
                storePassword = secret("RIPSTER_RELEASE_STORE_PASSWORD")
                keyAlias = secret("RIPSTER_RELEASE_KEY_ALIAS")
                keyPassword = secret("RIPSTER_RELEASE_KEY_PASSWORD")
                // Подписываем ВСЕМИ схемами: v1 (JAR) для древних сайдлоад-тулзов
                // и сканеров, что ругаются на «unsigned jar», v2/v3 — то, что
                // реально проверяет Android 8+. Без v1 некоторые анализаторы APK
                // ошибочно считают пакет «сломанным».
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Без R8/ProGuard: JAudiotagger/Coil/Room/Media3/kotlinx.serialization
            // потребовали бы keep-правила; для сайдлоада шринк не нужен.
            isMinifyEnabled = false
            // Подпишем только если секреты подписи заданы (см. secret() выше);
            // иначе release-APK останется неподписанным.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        prefab = true   // Oboe приезжает prefab-пакетом из AAR
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    // Объявлены ЯВНО, хотя сейчас приезжают транзитивно через material3.
    // План — уйти от M3; в день, когда его уберут, без этих строк отвалится всё
    // разом и по непонятной причине (находка коворка, раздел 5.1 его хэндоффа).
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")

    // --- Слой загрузки (Этап 0 плана ARCH_2026-08-30_mobile_engines_and_scope) ---
    // Пока без Room/Hilt/WorkManager: их добавит Этап 1 вместе с первым реальным
    // клиентом (SoundCloud), когда будет что через них гонять. Сейчас — только то,
    // что нужно, чтобы сетевой слой и модели компилировались и тестировались.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // Секреты сервисов (ARL, OAuth, пароли) — только в шифрованном сторе.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Очередь загрузок: Room (персист) + WorkManager (foreground-исполнение).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Запись в выбранную пользователем папку (SAF) — прямых путей на A11+ нет.
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Теги FLAC (Vorbis) / MP3 (ID3) / M4A (MP4) — Android-форк JAudiotagger.
    implementation("com.github.Adonai:jaudiotagger:2.3.15")
    // Аудиоплеер: ExoPlayer + MediaSession (BT-кнопки, аудиофокус, вывод).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    // Нативный аудиодвижок: Oboe (AAudio/OpenSL) для bit-perfect-пути.
    implementation("com.google.oboe:oboe:1.9.0")
    // Обложки (сеть → Compose).
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
