plugins {
    id("com.android.application")             version "8.6.1" apply false
    id("org.jetbrains.kotlin.android")        version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // KSP-версия ПРИБИТА к версии Kotlin (2.0.21) — рассинхрон = загадочные
    // ошибки генерации Room. Меняешь Kotlin — меняешь и это.
    id("com.google.devtools.ksp")            version "2.0.21-1.0.28" apply false
}
