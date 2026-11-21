import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
}

abstract class CopyGdxNativesTask : DefaultTask() {
    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val armeabiV7a: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val arm64V8a: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val x86: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val x8664: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyNatives() {
        val abiJars = mapOf(
            "armeabi-v7a" to armeabiV7a.files,
            "arm64-v8a" to arm64V8a.files,
            "x86" to x86.files,
            "x86_64" to x8664.files
        )
        fs.sync {
            into(outputDir)
            abiJars.forEach { (abi, jars) ->
                jars.forEach { jar ->
                    from(archives.zipTree(jar)) {
                        include("**/*.so")
                        into(abi)
                    }
                }
            }
        }
    }
}

val gdxAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

val gdxNativeConfigurations: Map<String, Configuration> = gdxAbis.associateWith { abi ->
    configurations.create("gdxNatives${abi.replace("-", "").replaceFirstChar(Char::uppercase)}")
}

android {
    namespace = "com.depthware.lwp.diffuse.v2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.depthware.lwp.diffuse.v2"
        minSdk = 24
        targetSdk = 35
        versionCode = 3434
        versionName = "1.3.0.0 (3434)-PUB"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += gdxAbis
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    val keystorePropsText = providers
        .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText
        .orNull
    val keystoreProps = Properties().apply {
        if (!keystorePropsText.isNullOrBlank()) {
            load(keystorePropsText.reader())
        }
    }
    val releaseStorePath = providers.environmentVariable("KEYSTORE_FILE").orNull
        ?: keystoreProps.getProperty("storeFile")
    val releaseStorePass = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
        ?: keystoreProps.getProperty("storePassword")
    val releaseKeyAliasVal = providers.environmentVariable("KEY_ALIAS").orNull
        ?: keystoreProps.getProperty("keyAlias")
    val releaseKeyPassVal = providers.environmentVariable("KEY_PASSWORD").orNull
        ?: keystoreProps.getProperty("keyPassword")

    signingConfigs {
        if (!releaseStorePath.isNullOrBlank() &&
            !releaseStorePass.isNullOrBlank() &&
            !releaseKeyAliasVal.isNullOrBlank() &&
            !releaseKeyPassVal.isNullOrBlank()
        ) {
            val candidateFile = rootProject.layout.projectDirectory.file(releaseStorePath).asFile
                .takeIf { it.exists() } ?: file(releaseStorePath).takeIf { it.exists() }
            if (candidateFile != null) {
                create("release") {
                    storeFile = candidateFile
                    storePassword = releaseStorePass
                    keyAlias = releaseKeyAliasVal
                    keyPassword = releaseKeyPassVal
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    lint {
        abortOnError = false
    }
}

val gdxNativesOutDir = layout.buildDirectory.dir("gdxNatives")

val copyGdxNatives by tasks.registering(CopyGdxNativesTask::class) {
    armeabiV7a.from(gdxNativeConfigurations.getValue("armeabi-v7a"))
    arm64V8a.from(gdxNativeConfigurations.getValue("arm64-v8a"))
    x86.from(gdxNativeConfigurations.getValue("x86"))
    x8664.from(gdxNativeConfigurations.getValue("x86_64"))
    outputDir.set(gdxNativesOutDir)
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            copyGdxNatives,
            CopyGdxNativesTask::outputDir
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.reactivestreams.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.slice.builders)
    implementation(libs.androidx.slice.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.gdx.core)
    implementation(libs.gdx.backend.android)
    gdxNativeConfigurations.forEach { (abi, configuration) ->
        configuration.dependencies.add(
            project.dependencies.create("${libs.gdx.platform.natives.get()}:natives-$abi")
        )
    }

    implementation(libs.rxjava3)
    implementation(libs.rxjava3.android)
    implementation(libs.joda.time)
    implementation(libs.orhanobut.logger)
    implementation(libs.android.image.cropper)
    implementation(libs.easypermissions)
    implementation(libs.google.play.review)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
