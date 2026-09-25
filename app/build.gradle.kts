import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zhongguoxiangqi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zhongguoxiangqi"
        minSdk = 26
        targetSdk = 36
        // Play 上传前必查已用过的 versionCode: 撞号会被拒("已有版本使用了版本
        // 代码"), 而这个错误只在 bundle 那一行显示为红字, 整个草稿版本会静默失效。
        // Play 对"上传过但未发布的版本号"也会永久保留: 4/5/6/7 都被占用,
        // 继续顺延。versionName 随版本号走, 保持用户可见版本一致。
        // 8 已在 2026-08-05 传过, 9 / 10 / 11 分别在 2026-08-16、08-17、08-17 发到
        // 内测轨道; 12 在 2026-09-10 发布 1.3.8 时上传正式版草稿(16KB 对齐被拒后
        // 从草稿移除, 但版本号已被 Play 永久保留); 13 同日因同一原因被烧 ——
        // 根因是 AGP 8.2 打包不做 16KB zip 对齐, 升级 AGP 8.5.2 后顺延到 14。
        versionCode = 15
        versionName = "1.3.9"

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
        create("upload") {
            // 从 ~/.android/qiyan-keystore.properties 读, 那个文件不进仓库。
            //
            // 单独一套而不是复用 AstroSimulator 的 keystore.properties ——
            // 两个应用共用一个上传密钥, 一旦其中一个要换密钥就会互相牵连;
            // 而且覆盖那个文件会让 AstroSimulator 再也无法更新。
            val ksProps = file(System.getProperty("user.home") + "/.android/qiyan-keystore.properties")
            if (ksProps.exists()) {
                val props = Properties().apply { ksProps.inputStream().use { load(it) } }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("upload")
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

    // 自研 NNUE 的求值核心走 C++/NEON。纯 Kotlin 标量实现实测比手写评估
    // 慢 20~60 倍, 折合少搜三四层, 网络再准也补不回来 —— 而 int8 点积正是
    // SIMD 最擅长的形状。这是我们自己的代码, 不涉及任何 GPL 依赖。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 仪器测试要在设备上读同一批黄金向量, 而 assets 是它唯一能可靠读到的
    // 地方。与其在仓库里放一份 663KB 的副本(两份迟早会不一致), 不如构建时
    // 从 test/resources 拷过去 —— 素材只有一份, 改了立刻两边都生效。
    sourceSets.getByName("androidTest") {
        assets.srcDir(layout.buildDirectory.dir("generated/androidTestAssets"))
    }

    testOptions {
        unitTests.all {
            // 让 -Dnnue.* 这类系统属性能透传进测试 JVM.
            // 默认 Gradle 不转发, 数据生成的参数会全部丢失退回默认值.
            it.systemProperties(System.getProperties().filterKeys { k ->
                k.toString().startsWith("nnue.") || k.toString().startsWith("match.") ||
                k.toString().startsWith("pika.") || k.toString().startsWith("xq.")
            }.mapKeys { e -> e.key.toString() })
            // 桌面 JVM 加载 x86-64 版 libxqnnue.so (build_host.sh 产出).
            // 没有这行时 System.loadLibrary 找不到 .so, NNUE 退到 Kotlin 标量.
            it.jvmArgs("-Djava.library.path=${project.file("src/main/cpp/host_lib").absolutePath}")
            it.maxHeapSize = "4g"
            // PikaFish JNI 测试在 JVM 退出时卸载 .so 会崩（宿主机特有）；
            // 每个测试类独立 JVM，把 PikafishNativeSmokeTest 与其它测试隔开。
            it.forkEvery = 1
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// PikaFish 权重（GPL-3）随 App 打包。50MB 不放仓库副本，构建时从
// third_party 复制到 assets（若已存在且同源则跳过）。
val copyPikafishNnue by tasks.registering {
    val src = rootProject.file("third_party/Pikafish/src/pikafish.nnue")
    val dstDir = layout.projectDirectory.dir("src/main/assets/models")
    val dst = dstDir.file("pikafish.nnue")
    inputs.file(src)
    outputs.file(dst)
    doLast {
        if (!src.exists()) {
            throw GradleException("缺少 PikaFish 权重: ${src.absolutePath}")
        }
        dstDir.asFile.mkdirs()
        if (!dst.asFile.exists() || dst.asFile.length() != src.length()) {
            src.copyTo(dst.asFile, overwrite = true)
            logger.lifecycle("已复制 PikaFish 权重到 assets (${src.length() / 1024 / 1024}MB)")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(copyPikafishNnue)
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
    // 1.22 起 JNI 壳库才带 16 KB 页面对齐 (1.20 的 libonnxruntime4j_jni.so
    // 还是 4KB 对齐, Play 会整包拒收), 升级时用 readelf 复核一遍.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // 实时预览支招: 摄像头常开 + 逐帧分析
    // 1.4.x 的 libimage_processing_util_jni.so 才是 16 KB 对齐 (1.3.4 不合规)
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

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
// 见上面 androidTest 的 assets 配置
val copyNnueFixtures by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("src/test/resources/nnue"))
    into(layout.buildDirectory.dir("generated/androidTestAssets/nnue"))
}
tasks.matching { it.name.contains("AndroidTest") }.configureEach {
    dependsOn(copyNnueFixtures)
}
