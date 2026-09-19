plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.toaster.noad"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.toaster.noad"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room 增量注解处理与 schema 导出目录
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // 让单元测试可访问 Room 导出的 schema（迁移测试用）
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)

    // ---- 协程 ----
    implementation(libs.kotlinx.coroutines.android)

    // ---- Room ----
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---- DataStore ----
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android 运行时由框架提供 org.json；JVM 单测需要同 API 的独立实现
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // 迁移测试需要真机/模拟器：SQLite 的真实行为无法在 JVM 上复现
    androidTestImplementation(libs.androidx.room.testing)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ----------------------------------------------------------------------------
// 依赖版本对齐
// ----------------------------------------------------------------------------
//
// `room-migration`（由 room-testing 引入）内部使用 kotlinx-serialization 1.8.1
// 解析 schema JSON，但 AGP 的 "consistent resolution" 会把主配置里
// 由其他库钉住的 1.7.3 以 `strictly` 形式传播到 androidTest 配置，
// 导致 1.8.1 被降级。
//
// 崩溃现象是 `AbstractMethodError: GeneratedSerializer.typeParametersSerializers()` ——
// 1.7.3 的序列化运行时与 Kotlin 2.2.10 编译出的生成代码 ABI 不兼容。
// 这个错误发生在测试框架内部，报错位置与真实原因完全无关，极难定位，
// 因此这里显式把版本对齐到 room 需要的下限，而不是逐个排除。
configurations.configureEach {
    resolutionStrategy {
        force(libs.kotlinx.serialization.json.get().toString())
    }
}
