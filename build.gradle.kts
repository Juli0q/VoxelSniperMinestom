val voxelSniperVersion = "v8.14.0"

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "de.ionnetwork"
version = "1.0"

repositories {
    mavenCentral()
    mavenLocal()                                     // de.ionnetwork:minestom-extension-api
    maven(url = "https://jitpack.io")                // Minestom, VoxelSniperCore
}

dependencies {
    // Provided by the running MinestomConversion server, never bundled: bundling either would put a
    // second copy of Minestom (or of the extension contract itself) on this jar's classloader, and
    // the loader hands both down from its parent.
    compileOnly("com.github.Minestom:Minestom:29988a8012") {
        exclude(group = "com.github.Minestom.Minestom", module = "testing")
    }
    compileOnly("de.ionnetwork:minestom-extension-api:1.0")
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // VoxelSniperCore, bundled. LGPL-2.1, which permits this - see the README.
    // The `v`-prefixed tag is deliberate: JitPack's build of the unprefixed `8.14.0` errors.
    implementation("com.github.KevinDaGame.VoxelSniper-Reimagined:VoxelSniperCore:$voxelSniperVersion")

    // Core's own build puts these in its `shadow` configuration, which the shadow plugin excludes
    // from its published POM - so core's classes reference them but they are absent from our
    // runtime classpath unless declared here ourselves. Versions match what core was built against.
    implementation("org.yaml:snakeyaml:1.33")
    implementation("com.google.code.gson:gson:2.10.1")

    // Adventure is deliberately NOT declared and NOT bundled: we bind to the server's copy.
    //
    // VoxelSniperCore is compiled against Adventure 4.13.1 and the server ships 5.2.0, which looks
    // like a breaking boundary but is not one for the surface core actually touches. Core's whole
    // Adventure footprint is 22 constant-pool references (Component, ComponentLike, TextComponent
    // and its builder, TextReplacementConfig, MiniMessage, LegacyComponentSerializer, NamedTextColor
    // and TextColor); all 22 resolve against 5.2.0, and executing core's message paths against the
    // real MinestomConversion jar produces byte-identical output to 4.13.1. See task-6-report.md for
    // the commands. Binding to the server's copy is what lets `IPlayer.sendMessage(Component)` hand
    // its argument straight to `Player.sendMessage(Component)` with no bridge.
    //
    // That count is a constant-pool scan, so it would miss a reflective `Class.forName("net.kyori…")`
    // path - core has no `Class.forName` call and no `net.kyori` string constant at all, so there is
    // none to miss, and the executed-paths check above covers it from the other direction anyway.
    //
    // Nothing is needed at compile time either: Minestom brings adventure-api transitively, and
    // that is the only Adventure module our own sources reference. Core additionally needs
    // adventure-text-minimessage and adventure-text-serializer-legacy *at runtime* - Minestom does
    // not depend on minimessage, but the MinestomConversion server jar bundles both unrelocated, so
    // the parent-first extension classloader finds them. A server build that stopped bundling
    // minimessage would break core's message rendering; that is the one thing to re-check here.

    // Dense position->block map for the staged change set. Same use, and same reason, as
    // WorldEditMinestom's EditBuffer: boxing a Long key per block is the difference between ~16 and
    // ~400 bytes per block.
    implementation("it.unimi.dsi:fastutil:8.5.18")

    testImplementation(kotlin("test"))
    testImplementation("com.github.Minestom:Minestom:29988a8012") {
        exclude(group = "com.github.Minestom.Minestom", module = "testing")
    }
    testImplementation("de.ionnetwork:minestom-extension-api:1.0")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

tasks.shadowJar {
    archiveFileName.set("VoxelSniperMinestom.jar")
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // Gradle-internal build metadata that some dependency jars happen to ship at their root;
    // harmless either way, but with duplicatesStrategy = INCLUDE above, Shadow would otherwise
    // report it as a duplicate on every build. No jar we currently bundle ships one - the Adventure
    // modules did, and they are no longer bundled - so this is a guard, not a live exclusion.
    exclude("classpath.index")

    // ExtensionLoader gives each jar a parent-first URLClassLoader, so any package the server's own
    // shaded jar also contains resolves to the server's copy, whatever version that is. Relocating
    // moves our copies to names nothing else owns.
    //
    // Deliberately NOT relocated:
    //  - com.github.kevindagame - VoxelSniper's own packages. Nothing else on the server has them.
    //  - net.kyori (Adventure) - we want the server's copy, and relocation is the one thing that
    //    would stop us getting it. Relocation is a post-compile rewrite applied to every class in
    //    the jar at once, so relocating would move core's `IPlayer.sendMessage(Component)` and our
    //    implementation of it to the shaded name while `Player.sendMessage` kept the server's -
    //    two Components that cannot meet without a reflective bridge. Binding to the server's copy
    //    instead is verified safe; see the dependencies block above.
    val shade = "de.ionnetwork.voxelsniper.minestom.libs"
    relocate("it.unimi.dsi", "$shade.fastutil")
    relocate("org.yaml.snakeyaml", "$shade.snakeyaml")
    relocate("com.google.gson", "$shade.gson")
    relocate("net.sandrohc.schematic4j", "$shade.schematic4j")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

/**
 * Drops the built extension into a MinestomConversion server's `extensions/` directory.
 * Point `-PextensionTarget=/path/to/server` at the test server.
 */
tasks.register<Copy>("deploy") {
    dependsOn(tasks.shadowJar)
    val target = providers.gradleProperty("extensionTarget")
        .orElse("${System.getProperty("user.home")}/Development/TestServers/IONNetwork/MinestomConversion")
    from(tasks.shadowJar)
    into(target.map { "$it/extensions" })
}
