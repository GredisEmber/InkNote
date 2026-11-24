// ============================================================================
// 根构建脚本 —— InkNote
//
// 这里只声明插件版本，不 apply（apply false）。
// 真正 apply 的地方在 :app/build.gradle.kts。
//
// ⚠️ AGP 9 内置 Kotlin 支持，所以这里没有 org.jetbrains.kotlin.android ——
// 单独声明它会报 Unresolved reference 'android'（catalog 里已经没有这个别名了），
// 就算有别名，apply 之后也会和 AGP 内置的 Kotlin 冲突。
// ============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
