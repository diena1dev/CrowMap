import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0"
    id("fabric-loom") version "1.17.17"
    kotlin("kapt") version "2.3.0"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 21
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("crowmap") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.

    mavenCentral()
    maven("https://maven.parchmentmc.org")
    maven("https://jitpack.io")
    maven("https://maven.wispforest.io")
}

// Graphene 2.1.0 ships its loader-independent API/runtime code (the
// io.github.trethore.graphene.api.*/.internal.* package everything in this
// project is written against, plus its bundled JCEF bindings) as TWO nested
// jars-in-a-jar inside graphene-ui-1.21.11 (META-INF/jars/common-*.jar and
// META-INF/jars/jcefgithub-*.jar), per Fabric Loader's nested-jar convention.
// Loom's compile-time dependency resolution doesn't expose nested jars at all
// (breaks compilation), AND Loom's dev-run remap step silently drops them
// from the remapped mod jar it hands to Fabric Loader for `runClient`
// (verified: the remapped jar only contains the ~35 fabric-integration
// classes, none of the nested jars') — causing NoClassDefFoundError at
// runtime (first for io.github.trethore.graphene.internal.runtime.*, then
// for io.github.trethore.jcefgithub.* once the first was fixed) even though
// compilation succeeds. So we extract both nested jars ourselves and put them
// on the runtime classpath directly.
val grapheneVersion = "2.1.0"

val grapheneUiJar: Configuration by configurations.creating

val extractGrapheneNestedJars by tasks.registering(Copy::class) {
    from(zipTree(grapheneUiJar.singleFile)) {
        include("META-INF/jars/*.jar")
        eachFile { relativePath = RelativePath(true, name) }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("graphene-extracted"))
}

val grapheneExtractedJars = fileTree(layout.buildDirectory.dir("graphene-extracted")) {
    include("*.jar")
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${project.property("minecraft_version")}:2025.12.20@zip")
    })

    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    modImplementation("io.github.trethore:graphene-ui-1.21.11:$grapheneVersion")
    grapheneUiJar("io.github.trethore:graphene-ui-1.21.11:$grapheneVersion") { isTransitive = false }
    // implementation (not compileOnly) so this also lands on the runtime classpath —
    // Loom's include() rejects a bare file collection ("not a module component"), so this
    // can't be jar-in-jar'd into CrowMap's own distributed jar; it's compile+runClient only.
    // If a shipped build hits the same NoClassDefFoundError, this jar needs bundling some
    // other way (e.g. shading it into the built jar's classes directly).
    implementation(files(grapheneExtractedJars).builtBy(extractGrapheneNestedJars))
    modImplementation("io.wispforest:owo-lib:0.13.0-alpha.16+1.21.11")
    // owo-lib annotation processor – generates CrowmapConfigWrapper from CrowmapConfigModel.
    // Uses kapt so the generated wrapper is visible during Kotlin compilation.
    kapt("io.wispforest:owo-lib:0.13.0-alpha.16+1.21.11")
    // The config model lives in the 'client' source set, so kapt must run there too.
    "kaptClient"("io.wispforest:owo-lib:0.13.0-alpha.16+1.21.11")

    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okio:okio:3.17.0")

    include("com.squareup.okio:okio:3.17.0")
    include("com.squareup.okhttp3:okhttp:5.3.2")
    include("io.wispforest:owo-lib:0.13.0-alpha.16+1.21.11")
}

tasks.processResources {
    inputs.property("version", project!!.version)
    inputs.property("minecraft_version", project!!.property("minecraft_version"))
    inputs.property("loader_version", project!!.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version as Any,
            "minecraft_version" to project.property("minecraft_version") as Any,
            "loader_version" to project.property("loader_version") as Any,
            "kotlin_loader_version" to project.property("kotlin_loader_version") as Any
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
