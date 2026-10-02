import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonOutput
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    kotlin("android") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// Use the actual variant's runtime artifacts, including developer builds. Some
// OSS tooling relies on an AGP dependency report that is absent for debug APKs.
abstract class GenerateNoticeAssets @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:InputFile abstract val generatorScript: RegularFileProperty
    @get:InputFiles abstract val projectNoticeFiles: ConfigurableFileCollection
    @get:InputFiles abstract val runtimeArchives: ConfigurableFileCollection
    @get:Internal lateinit var runtimeConfiguration: Configuration
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @get:Internal abstract val reportDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val artifacts = runtimeConfiguration.incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }.artifacts.artifacts.sortedBy { it.id.componentIdentifier.displayName }
        val components = artifacts.map { it.id.componentIdentifier }.distinct()
        val poms = project.dependencies.createArtifactResolutionQuery()
            .forComponents(components).withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
            .execute().resolvedComponents.associate { component ->
                val pom = component.getArtifacts(MavenPomArtifact::class.java)
                    .filterIsInstance<ResolvedArtifactResult>().singleOrNull()
                    ?: error("Missing runtime POM for ${component.id.displayName}")
                component.id to pom.file
            }
        val catalog = artifacts.map { artifact ->
            val component = artifact.id.componentIdentifier as ModuleComponentIdentifier
            mapOf("coordinate" to "${component.group}:${component.module}:${component.version}",
                "archive" to artifact.file.absolutePath,
                "pom" to (poms[component] ?: error("Unresolved runtime POM for ${component.displayName}")).absolutePath)
        }
        val report = reportDirectory.get().asFile.apply { mkdirs() }
        val manifest = report.resolve("runtime-inputs.private.json")
        manifest.writeText(JsonOutput.toJson(catalog), Charsets.UTF_8)
        execOperations.exec {
            commandLine("python3", generatorScript.get().asFile.absolutePath,
                "--catalog", manifest.absolutePath, "--source-root", sourceRoot.get().asFile.absolutePath,
                "--output", outputDirectory.get().asFile.absolutePath,
                "--report", report.resolve("coverage.json").absolutePath)
        }.assertNormalExitValue()
    }
}

subprojects {
    plugins.withId("com.android.application") {
        extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
            val noticeTask = tasks.register<GenerateNoticeAssets>("generate${variant.name.replaceFirstChar { it.uppercaseChar() }}NoticeAssets") {
                runtimeConfiguration = variant.runtimeConfiguration
                runtimeArchives.from(variant.runtimeConfiguration.incoming.artifactView {
                    componentFilter { it is ModuleComponentIdentifier }
                }.files)
                generatorScript.set(rootProject.layout.projectDirectory.file("scripts/generate_notices.py"))
                sourceRoot.set(rootProject.layout.projectDirectory)
                projectNoticeFiles.from(rootProject.files("LICENSE", "NOTICE", "docs/THIRD_PARTY_NOTICES.md"))
                outputDirectory.set(layout.buildDirectory.dir("generated/notice-assets/${variant.name}"))
                reportDirectory.set(layout.buildDirectory.dir("reports/licenses/${variant.name}"))
                // POM metadata is resolved at execution; always rebuild its coverage.
                outputs.upToDateWhen { false }
            }
            variant.sources.assets?.addGeneratedSourceDirectory(noticeTask, GenerateNoticeAssets::outputDirectory)
                ?: error("Assets are unavailable for ${project.path}:${variant.name}")
        }
    }
}
