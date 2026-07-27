import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zhongguoxiangqi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zhongguoxiangqi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // AI 分析的代理配置: 优先 local.properties, 其次 gradle property,
        // 再其次环境变量. 缺一就让 build 警告但不断 build, 装到设备上
        // 运行时再告诉用户"未配置".
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val advisorProxyBaseUrl = localProps.getProperty("qiyanAdvisorProxyBaseUrl")
            ?: providers.gradleProperty("qiyanAdvisorProxyBaseUrl")
                .orElse(providers.environmentVariable("QIYAN_ADVISOR_PROXY_BASE_URL"))
                .getOrElse("")
        val advisorProxyToken = localProps.getProperty("qiyanAdvisorProxyToken")
            ?: providers.gradleProperty("qiyanAdvisorProxyToken")
                .orElse(providers.environmentVariable("QIYAN_ADVISOR_PROXY_TOKEN"))
                .getOrElse("")
        if (advisorProxyBaseUrl.isBlank() || advisorProxyToken.isBlank()) {
            logger.warn(
                "\n⚠ AI 分析未配置：qiyanAdvisorProxyBaseUrl/Token 为空, 装到设备上会报\"未配置云端代理地址\".\n" +
                    "  请在 local.properties 中加入:\n" +
                    "    qiyanAdvisorProxyBaseUrl=https://<your-proxy-host>/anthropic\n" +
                    "    qiyanAdvisorProxyToken=<PROXY_ACCESS_TOKEN>\n"
            )
        }

        buildConfigField("String", "ADVISOR_PROXY_BASE_URL", "\"$advisorProxyBaseUrl\"")
        buildConfigField("String", "ADVISOR_PROXY_TOKEN", "\"$advisorProxyToken\"")
        buildConfigField("String", "ADVISOR_MODEL", "\"MiniMax-M3\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Pikafish 只编了 arm64-v8a, 其它 ABI 上引擎本来就跑不起来.
        // 而 ONNX Runtime 默认带 4 个 ABI, 白白多占 ~53MB, 全部裁掉.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 用户自行配置签名
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
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }


    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // ONNX 模型已是压缩过的浮点权重, 再压一遍只是让安装变慢,
    // 而且不压缩才能直接 mmap 读取, 省掉一次解压到 filesDir.
    androidResources {
        noCompress += listOf("onnx", "nnue")
    }

    testOptions {
        unitTests.all {
            // 让 -Dnnue.* 这类系统属性能透传进测试 JVM.
            // 默认 Gradle 不转发, 数据生成的参数会全部丢失退回默认值.
            it.systemProperties(System.getProperties().filterKeys { k ->
                k.toString().startsWith("nnue.") || k.toString().startsWith("match.")
            }.mapKeys { e -> e.key.toString() })
            it.maxHeapSize = "4g"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 固定用 JDK 17 编译: 与上面声明的 source/target 一致,
// 也不受运行 Gradle 的 JVM 版本影响(JDK 21 的 headless 包缺 jlink,会让 AGP 失败).
kotlin {
    jvmToolchain(17)
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room (game database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Preferences DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Navigation (optional but useful)
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ONNX Runtime —— 拍照识别棋局 (棋盘四角关键点 + 棋子分类)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // 实时预览支招: 摄像头常开 + 逐帧分析
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Anthropic SDK —— AI 分析功能, 走 AstroSimulator 的本地代理
    implementation("com.anthropic:anthropic-java:2.52.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // android.jar 里的 org.json 只是空壳,单元测试需要真实现来校验开局库数据
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}