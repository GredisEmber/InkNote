// ============================================================================
// :app 模块构建脚本 —— InkNote
//
// ⚠️ 这份脚本用的是 AGP 9 的 DSL，和 AGP 8 及以前不一样：
//   · compileSdk { version = release(37) }   取代 compileSdk = 37
//   · buildTypes { release { optimization { enable = false } } }
//                                             取代 isMinifyEnabled = false
//   · 不再 apply org.jetbrains.kotlin.android：AGP 9 内置 Kotlin，
//     单独 apply 会和内置的冲突。Compose 只需要额外的编译器插件。
//   · 不再需要 kotlin { compilerOptions { jvmTarget } }：
//     内置 Kotlin 会按 compileOptions 自动对齐 JVM 目标，手写反而容易写歪。
// ============================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Room 的注解处理器
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.inknote.pad"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.inknote.pad"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // debug 故意不加 applicationIdSuffix：
        // 个人自用 App，debug 和 release 共用同一个 applicationId，
        // 这样装 release 覆盖 debug 时笔记数据不会「消失」。
        release {
            optimization {
                // R8 关闭。等代码稳定、要压体积时再打开，
                // 届时 proguard-rules.pro 才会被接进来。
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    // 把 Room 的 schema JSON 导出到 app/schemas。
    //
    // 手写笔记是会长期积累、不可再生的数据，而 schema 一定会变
    // （Step 5 要加页、以后可能要加标签）。没有历史 schema 就没法写可靠的
    // Migration，只能 destroyAndRecreate —— 那等于把用户的笔记删掉。
    // 这个目录应该提交进版本库。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // 只用于 debug：Compose 布局检查器 / @Preview 渲染
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM 单元测试：验压感曲线、平滑几何、命中测试、.ink 文件格式
    testImplementation(libs.junit)

    // 持久化：Room 存笔记元数据。compiler 走 KSP（不是 kapt）
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
}
