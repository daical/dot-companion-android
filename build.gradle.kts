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
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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
        val parentCache = mutableMapOf<String, java.io.File>()
        fun parentPoms(initialFile: java.io.File, initialCoordinate: String): List<Map<String, String>> {
            val parents = mutableListOf<Map<String, String>>()
            val seen = mutableSetOf(initialCoordinate)
            var current = initialFile
            while (true) {
                val pom = readPom(current)
                if (childElement(pom, "licenses")?.let { childElements(it, "license").isNotEmpty() } == true) break
                val parent = childElement(pom, "parent") ?: break
                val parts = listOf("groupId", "artifactId", "version").map { field ->
                    childElement(parent, field)?.textContent?.trim() ?: error("Incomplete parent POM metadata")
                }
                require(parts[0].matches(Regex("[A-Za-z0-9_.-]+")) && parts[1].matches(Regex("[A-Za-z0-9_.-]+"))
                    && parts[2].matches(Regex("[A-Za-z0-9_.+-]+"))) { "Unsupported parent POM coordinate" }
                val coordinate = parts.joinToString(":")
                require(parents.size < 16 && seen.add(coordinate)) { "Cyclic or excessive parent POM hierarchy" }
                current = parentCache.getOrPut(coordinate) {
                    project.dependencies.createArtifactResolutionQuery().forModule(parts[0], parts[1], parts[2])
                        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
                        .resolvedComponents.flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
                        .filterIsInstance<ResolvedArtifactResult>().singleOrNull()?.file
                        ?: error("Unresolved license parent POM: $coordinate")
                }
                parents.add(mapOf("coordinate" to coordinate, "pom" to current.absolutePath))
            }
            return parents
        }
        val catalog = artifacts.map { artifact ->
            val component = artifact.id.componentIdentifier as ModuleComponentIdentifier
            val coordinate = "${component.group}:${component.module}:${component.version}"
            val pom = poms[component] ?: error("Unresolved runtime POM for ${component.displayName}")
            mapOf("coordinate" to coordinate,
                "archive" to artifact.file.absolutePath,
                "pom" to pom.absolutePath, "parentPoms" to parentPoms(pom, coordinate))
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

    private fun readPom(file: java.io.File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            // Use the JAXP property URIs directly: Gradle's compile classpath can
            // contain an older xml-apis XMLConstants without these Java 7 fields.
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun childElement(parent: Element, name: String): Element? {
        val matching = childElements(parent, name)
        require(matching.size <= 1) { "Duplicate $name in POM metadata" }
        return matching.singleOrNull()
    }

    private fun childElements(parent: Element, name: String): List<Element> =
        (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }
            .filter { (it.localName ?: it.tagName) == name }
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
