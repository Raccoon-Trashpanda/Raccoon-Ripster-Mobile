import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Подпись релиза (трекер #42). Пароли не в этом файле — он отслеживается
// публичным зеркалом Raccoon-Ripster-Mobile. Носители, в порядке приоритета:
// signing.properties в корне репозитория (в .gitignore) и переменные окружения
// RIPSTER_STORE_PASSWORD / RIPSTER_KEY_PASSWORD. Ключевой файл (ripster-release.jks)
// в git не лежал никогда, а вот пароль утекал в публичный репозиторий вместе с
// этим скриптом; ротация ключа — решение владельца (новым ключом не обновить
// уже установленные сборки).
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Имя env-переменной задаём явно: "storePassword".uppercase() дало бы
// RIPSTER_STOREPASSWORD (слитно), а обещаем по задаче RIPSTER_STORE_PASSWORD.
fun signingSecret(name: String, env: String): String? =
    (signingProps.getProperty(name) ?: System.getenv(env))
        ?.takeIf { it.isNotBlank() }

val releaseStorePassword = signingSecret("storePassword", "RIPSTER_STORE_PASSWORD")
val releaseKeyPassword = signingSecret("keyPassword", "RIPSTER_KEY_PASSWORD") ?: releaseStorePassword

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
        versionCode = 48
        versionName = "0.48"

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

        // Инструированные тесты (раунд 3, ротация) — владелец завёл androidTest
        // специально под них.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            storeFile = rootProject.file("ripster-release.jks")
            keyAlias = "ripster"
            // Пароли в источник не кладём (трекер #42): этот файл отслеживается
            // публичным зеркалом. Значения приходят из gitignored
            // signing.properties в корне или из окружения RIPSTER_*; если их
            // нет — сборку валит ниже явной ошибкой, а не тихой подменой.
            releaseStorePassword?.let { storePassword = it }
            releaseKeyPassword?.let { keyPassword = it }
            // Подписываем ВСЕМИ схемами: v1 (JAR) для древних сайдлоад-тулзов и
            // сканеров, что ругаются на «unsigned jar», v2/v3 — то, что реально
            // проверяет Android 8+. Без v1 некоторые анализаторы APK ошибочно
            // считают пакет «сломанным».
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // Без R8/ProGuard: JAudiotagger/Coil/Room/Media3/kotlinx.serialization
            // потребовали бы keep-правила; для сайдлоада шринк не нужен.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    // ViewModel + SavedStateHandle: раунд 3 (ротация). Состояние запроса/данных
    // должно переживать пересоздание активности, а не только Bundle. Берём
    // lifecycle 2.8.x — та же ветка, что тянет Compose BOM 2024.09.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.3")

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
    // «Стекло» для «дорогих визуалов» (Haze) — Maven Central, версия 0.7.3:
    // последняя, чьи транзитивные зависимости (compose 1.6.7, core-ktx 1.13.1)
    // не требуют compileSdk 35. На этом дереве выше — конфликт с BOM 2024.09.
    implementation("dev.chrisbanes.haze:haze:0.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Живые тесты ротации на приборе (раунд 3). ВНИМАНИЕ: в этой среде
    // dl.google.com отвечает 404 на любой путь (включая заведомо существующие
    // артефакты) — реестр Google недоступен, поэтому connected-прогон здесь
    // физически не собирается: androidx.test и ui-test-junit4 есть только там.
    // Сами тесты в app/src/androidTest написаны и включатся с сетью;
    // фактическая проверка ротации на приборе в раунде 3 сделана через
    // uiautomator- снапшоты (test_reports/r3_rot.py) — см. round_03.md.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}

// Громкий отказ вместо тихой подмены (трекер #42): если пароля подписи нет,
// release-сборка обязана назвать недостающее — а не подписать отладочным
// ключом или упасть с невнятным «keystore password was incorrect» в depths AGP.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any {
        it.project.path == ":app" &&
            (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("install")) &&
            it.name.contains("Release")
    }
    if (!wantsRelease) return@whenReady
    val missing = listOfNotNull(
        if (releaseStorePassword == null) "storePassword" else null,
        if (releaseKeyPassword == null) "keyPassword" else null,
    )
    if (missing.isNotEmpty()) {
        error(
            "Релиз не соберётся (трекер #42): нет пароля подписи — " + missing.joinToString(" и ") + ". " +
                "Положите его в signing.properties в корне проекта (файл в .gitignore): " +
                "storePassword=… (и keyPassword=…, если отличается от storePassword) " +
                "либо задайте окружение RIPSTER_STORE_PASSWORD / RIPSTER_KEY_PASSWORD. " +
                "Отладочным ключом релиз молча не подписывается.",
        )
    }
    if (!rootProject.file("ripster-release.jks").exists()) {
        error(
            "Релиз не соберётся (трекер #42): нет файла ключа ripster-release.jks в " +
                rootProject.projectDir + " — он тоже намеренно вне git.",
        )
    }
}
