import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import javax.inject.Inject

plugins {
    id("com.android.library")
}

val sourceMode = rootProject.name == "NestSpace"
val sourceAssets = layout.projectDirectory.dir("src/main/plugin-assets")
val sourceAssetsDirectory = sourceAssets
val sourceManifest = sourceAssets.file("manifest.json").asFile
val sourcePluginId = Regex("\"id\"\\s*:\\s*\"([a-z0-9][a-z0-9._-]+)\"")
    .find(sourceManifest.readText())
    ?.groupValues
    ?.get(1)
    ?: error("Plugin manifest is missing a valid id: $sourceManifest")
val pluginApiAar = providers.gradleProperty("nestspacePluginApiAar")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: rootProject.file(
        "../plugin-api/build/outputs/aar/plugin-api-debug.aar"
    ).absolutePath

android {
    namespace = "com.example.runtimedemo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    if (!sourceMode) {
        sourceSets {
            getByName("main") {
                assets.srcDir(sourceAssets)
            }
        }
    }
}

abstract class PrepareSourcePluginAssets : DefaultTask() {
    @get:InputDirectory
    abstract val inputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val assetPath: Property<String>

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        fileSystemOperations.delete {
            delete(outputDirectory)
        }
        fileSystemOperations.sync {
            from(inputDirectory)
            into(outputDirectory.dir(assetPath.get()))
        }
    }
}

abstract class PrepareSourcePluginShareAsset : DefaultTask() {
    @get:InputDirectory
    abstract val sourceAssets: DirectoryProperty

    @get:InputFiles
    abstract val classDirectories: ConfigurableFileCollection

    @get:InputFile
    abstract val androidJar: RegularFileProperty

    @get:Input
    abstract val d8Path: Property<String>

    @get:Input
    abstract val pluginId: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        val work = File(temporaryDir, "share-package")
        work.deleteRecursively()
        val classesJar = File(work, "classes.jar")
        classesJar.parentFile.mkdirs()
        JarOutputStream(classesJar.outputStream()).use { jar ->
            classDirectories.files
                .filter { it.isDirectory }
                .forEach { root ->
                    root.walkTopDown()
                        .filter { it.isFile }
                        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                        .forEach { file ->
                            jar.putNextEntry(
                                JarEntry(
                                    file.relativeTo(root)
                                        .invariantSeparatorsPath
                                )
                            )
                            file.inputStream().use { it.copyTo(jar) }
                            jar.closeEntry()
                        }
                }
        }
        val dexDirectory = File(work, "dex").apply { mkdirs() }
        execOperations.exec {
            commandLine(
                d8Path.get(),
                "--min-api",
                "24",
                "--lib",
                androidJar.get().asFile.absolutePath,
                "--output",
                dexDirectory.absolutePath,
                classesJar.absolutePath
            )
        }
        val archive = File(work, "${pluginId.get()}.nestplugin")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            val assetsRoot = sourceAssets.get().asFile
            assetsRoot.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(assetsRoot).invariantSeparatorsPath }
                .forEach { file ->
                    zip.putNextEntry(
                        ZipEntry(
                            file.relativeTo(assetsRoot)
                                .invariantSeparatorsPath
                        )
                    )
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            val dex = File(dexDirectory, "classes.dex")
            require(dex.isFile) {
                "source plugin export did not produce classes.dex"
            }
            zip.putNextEntry(ZipEntry("classes.dex"))
            dex.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        val output = outputDirectory.get().asFile
        fileSystemOperations.delete { delete(output) }
        val target = File(
            output,
            "source-plugin-packages/${pluginId.get()}.nestplugin"
        )
        target.parentFile.mkdirs()
        check(archive.renameTo(target)) {
            "source plugin export asset could not be finalized"
        }
    }
}

val prepareSourcePluginAssets =
    tasks.register<PrepareSourcePluginAssets>("prepareSourcePluginAssets") {
        inputDirectory.set(sourceAssets)
        outputDirectory.set(
            layout.buildDirectory.dir("generated/sourcePluginAssets")
        )
        assetPath.set("plugins-dev/$sourcePluginId")
    }

if (sourceMode) {
    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                prepareSourcePluginAssets,
                PrepareSourcePluginAssets::outputDirectory
            )
        }
    }
}

dependencies {
    if (sourceMode) {
        compileOnly(project(":plugin-api"))
    } else {
        compileOnly(files(pluginApiAar))
    }
}

val androidSdk = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orNull
    ?.let(::file)
    ?: rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("sdk.dir=")
        ?.let(::file)
val d8 = androidSdk?.resolve("build-tools/36.0.0/d8")
val androidJar = androidSdk?.resolve("platforms/android-37.0/android.jar")
val stageDir = layout.buildDirectory.dir("plugin-package")
val aarFile = layout.buildDirectory.file("outputs/aar/plugin-debug.aar")
val packageManifest = if (sourceMode) {
    project.file("../manifest.json")
} else {
    rootProject.file("manifest.json")
}

tasks.register<Sync>("stagePluginPackage") {
    dependsOn("assembleDebug")
    into(stageDir)
    from({ zipTree(aarFile.get().asFile) }) {
        include("classes.jar")
    }
    from("src/main/plugin-assets") {
        exclude("manifest.json")
    }
    from(packageManifest)
}

if (androidSdk != null && d8 != null && androidJar != null) {
    if (sourceMode) {
        val shareAndroidJar = androidJar
        val shareD8 = d8
        val prepareSourcePluginShareAsset =
            tasks.register<PrepareSourcePluginShareAsset>(
                "prepareSourcePluginShareAsset"
            ) {
                dependsOn("compileDebugJavaWithJavac")
                sourceAssets.set(sourceAssetsDirectory)
                classDirectories.from(
                    layout.buildDirectory.dir(
                        "intermediates/javac/debug/" +
                            "compileDebugJavaWithJavac/classes"
                    ),
                    layout.buildDirectory.dir(
                        "tmp/kotlin-classes/debug"
                    )
                )
                androidJar.set(shareAndroidJar)
                d8Path.set(shareD8.absolutePath)
                pluginId.set(sourcePluginId)
                outputDirectory.set(
                    layout.buildDirectory.dir(
                        "generated/sourcePluginShareAssets"
                    )
                )
            }
        androidComponents {
            onVariants { variant ->
                variant.sources.assets?.addGeneratedSourceDirectory(
                    prepareSourcePluginShareAsset,
                    PrepareSourcePluginShareAsset::outputDirectory
                )
            }
        }
    }

    tasks.register<Exec>("dexPluginPackage") {
        dependsOn("stagePluginPackage")
        val outputDirectory = stageDir.get().asFile
        inputs.file(stageDir.map { it.file("classes.jar") })
        outputs.file(stageDir.map { it.file("classes.dex") })
        doFirst {
            outputDirectory.mkdirs()
        }
        commandLine(
            d8.absolutePath,
            "--min-api",
            "24",
            "--lib",
            androidJar.absolutePath,
            "--output",
            outputDirectory.absolutePath,
            outputDirectory.resolve("classes.jar").absolutePath
        )
    }

    tasks.register<Zip>("packagePlugin") {
        dependsOn("dexPluginPackage")
        archiveFileName.set("runtime-demo-plugin.nestplugin")
        destinationDirectory.set(
            rootProject.layout.buildDirectory.dir("plugin-packages")
        )
        from(stageDir) {
            exclude("classes.jar")
        }
    }
}
