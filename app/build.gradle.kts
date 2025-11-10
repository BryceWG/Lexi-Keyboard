plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

android {
    namespace = "com.brycewg.asrkb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brycewg.asrkb"
        minSdk =29
        targetSdk = 35
        versionCode = 106
        versionName = "3.6.6"

        // 仅打包 arm64-v8a 以控制体积；可按需扩展
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 从环境变量读取签名配置
            storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // 统一开启代码压缩和混淆
            isMinifyEnabled = true
            // 统一开启资源压缩
            isShrinkResources = true

            // 通用规则文件（仅包含共享规则）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 如果签名配置可用,则使用签名
            signingConfig = signingConfigs.findByName("release")?.takeIf {
                it.storeFile?.exists() == true
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // 构建开源版(oss) 与 商店专业版(pro)
    flavorDimensions += "edition"
    productFlavors {
        create("oss") {
            dimension = "edition"
            // 开源版包名沿用主包
            applicationId = "com.brycewg.asrkb"
            // 版本常量：供代码中区分功能
            buildConfigField("boolean", "IS_PRO", "false")
            buildConfigField("String", "EDITION", "\"OSS\"")

            // OSS 专属规则（-dontobfuscate）
            proguardFile("proguard-oss.pro")
        }
        create("pro") {
            dimension = "edition"
            // 专业版单独包名，便于并行安装与单独上架
            applicationId = "com.brycewg.asrkb.pro"
            buildConfigField("boolean", "IS_PRO", "true")
            buildConfigField("String", "EDITION", "\"PRO\"")
            // 覆盖应用名，避免在仓库内新增 pro 资源文件
            resValue("string", "app_name", "言犀键盘 Pro")

            // Pro 专属规则（完整混淆）
            proguardFile("src/pro/proguard-pro.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // 显式声明 AIDL 源目录，确保各变体均能解析 AIDL 接口
    sourceSets {
        getByName("main") {
            aidl.srcDirs("src/main/aidl")
        }
    }

    // 由于应用在运行时支持手动切换语言，禁用 App Bundle 的按语言拆分，
    bundle {
        language {
            enableSplit = false
        }
    }
    // - 排除不需要的 JNI 库：
    //   * libonnxruntime4j_jni.so（ORT-Java 绑定，不使用，且常见 16KB 对齐问题）
    //   * libsherpa-onnx-c-api.so / libsherpa-onnx-cxx-api.so（C/C++ 接口，本项目走 JNI 不需要）
    packaging {
        jniLibs {
            excludes += listOf(
                "**/libonnxruntime4j_jni.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so"
            )
        }
        // 移除 Lombok 的 ServiceLoader 声明，避免 R8 对缺失实现类的告警
        resources {
            excludes += listOf(
                "META-INF/services/lombok.*",
                "README.md",
                "META-INF/README.md"
            )
        }
    }
}

// Kotlin 编译配置：使用 compilerOptions DSL 与 JDK 17 工具链
kotlin {
    // 使用本机 jbr-21 作为工具链，但 Kotlin 仍产出 JVM 17 目标字节码
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 让 Java 编译任务使用本机 JDK 21 工具链，同时保持源码/目标兼容为 17
val toolchainService = extensions.getByType(JavaToolchainService::class.java)
tasks.withType(JavaCompile::class.java).configureEach {
    javaCompiler.set(
        toolchainService.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    
    implementation("com.alibaba:dashscope-sdk-java:2.21.15")

    // AAR 本地依赖占位：将 sherpa-onnx Kotlin API AAR 放入 app/libs/ 后自动识别
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Pro 变体
    add("proImplementation", "androidx.work:work-runtime-ktx:2.9.1")
    add("proImplementation", "com.github.houbb:opencc4j:1.14.0")
}
