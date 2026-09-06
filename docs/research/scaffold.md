# Research: scaffold

# RuneLite Hub Plugin Scaffold & Toolchain (verified against GitHub master + repo.runelite.net, 2026-09-05)

## Starting point
The canonical way to start a hub plugin is generating a repo from the template at `github.com/runelite/example-plugin` (GitHub "Use this template" / `/generate` link), or the `create_new_plugin.py` script in `github.com/runelite/plugin-hub-tooling`. The repo MUST be public on GitHub for hub submission.

## Exact current build.gradle (example-plugin master, verbatim)
```gradle
plugins {
	id 'java'
}

repositories {
	mavenLocal()
	maven {
		url = 'https://repo.runelite.net'
		content {
			includeGroupByRegex("net\\.runelite.*")
		}
	}
	mavenCentral()
}

def runeLiteVersion = 'latest.release'
def pluginMainClass = 'com.example.ExamplePluginTest'

dependencies {
	compileOnly group: 'net.runelite', name:'client', version: runeLiteVersion

	compileOnly 'org.projectlombok:lombok:1.18.30'
	annotationProcessor 'org.projectlombok:lombok:1.18.30'

	testImplementation 'junit:junit:4.12'
	testImplementation group: 'net.runelite', name:'client', version: runeLiteVersion
	testImplementation group: 'net.runelite', name:'jshell', version: runeLiteVersion
}

group = 'com.example'

tasks.withType(JavaCompile).configureEach {
	options.encoding = 'UTF-8'
	options.release.set(11)
}

tasks.register('run', JavaExec) {
	classpath = sourceSets.test.runtimeClasspath
	mainClass = pluginMainClass

	jvmArgs "-ea"
	args "--developer-mode", "--debug"
}

tasks.register('shadowJar', Jar) {
	dependsOn configurations.testRuntimeClasspath
	manifest {
		attributes('Main-Class': pluginMainClass, 'Multi-Release': true)
	}

	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from sourceSets.main.output
	from sourceSets.test.output
	from {
		configurations.testRuntimeClasspath.collect { file ->
			file.isDirectory() ? file : zipTree(file)
		}
	}

	exclude 'META-INF/INDEX.LIST'
	exclude 'META-INF/*.SF'
	exclude 'META-INF/*.DSA'
	exclude 'META-INF/*.RSA'
	exclude '**/module-info.class'

	group = BasePlugin.BUILD_GROUP
	archiveClassifier.set('shadow')
	archiveFileName.set("${rootProject.name}-${project.version}-all.jar")
}
```
`settings.gradle` is one line: `rootProject.name = 'example'` (rename it). Note: the template now also pulls `net.runelite:jshell` as a testImplementation dep (new vs older templates).

## Versions / toolchain
- runelite-client dependency scheme: `net.runelite:client:latest.release` from `https://repo.runelite.net` (Gradle dynamic version). Current `<release>` in maven-metadata.xml: **1.12.38** (latest snapshot 1.12.39-SNAPSHOT, metadata lastUpdated 2026-09-03). Keep `latest.release` â€” the hub README explicitly says leaving it as `latest.release` and refreshing Gradle dependencies is the fix for "client version outdated".
- JDK: **Java 11** (plugin-hub README recommends IntelliJ IDEA CE + Eclipse Temurin 11 from adoptium.net; the hub CI builds with `actions/setup-java` distribution `adopt`, `java-version: 11` on ubuntu-24.04). Compile target is enforced by `options.release.set(11)`; all code must be Java 11 compatible.
- Gradle wrapper IS included: **Gradle 8.10** (`gradle-wrapper.properties`: distributionUrl `https://services.gradle.org/distributions/gradle-8.10-all.zip`, sha256 `682b4df7fe5accdca84a4d1ef6a3a6ab096b3efd5edf7de2bd8c758d95a93703`); `gradlew` and `gradlew.bat` both present.
- Lombok 1.18.30 (compileOnly + annotationProcessor), JUnit 4.12.

## Project layout (example-plugin master tree)
```
.gitignore  AGENTS.md  CLAUDE.md  README.md
build.gradle  settings.gradle  runelite-plugin.properties
gradlew  gradlew.bat  gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
src/main/java/com/example/ExamplePlugin.java
src/main/java/com/example/ExampleConfig.java
src/test/java/com/example/ExamplePluginTest.java
src/test/resources/logback-test.xml
```
CLAUDE.md content is literally `@AGENTS.md` (points at AGENTS.md, which carries official agent-facing dev guidelines â€” see keyFacts). Optional `icon.png` goes at repo ROOT. Resources (images/sounds) go in `src/main/resources` and must be loaded via `Class.getResourceAsStream()` (NOT `getResource()` â€” hub plugins run from a jar, never unpacked on disk).

## Test-main-class run pattern (verbatim ExamplePluginTest.java)
```java
package com.example;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ExamplePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ExamplePlugin.class);
		RuneLite.main(args);
	}
}
```
`ExternalPluginManager.loadBuiltin(Class<? extends Plugin>...)` is varargs â€” can load multiple plugin classes. This class lives in src/test and is launched by the `run` Gradle task with JVM arg `-ea` and program args `--developer-mode --debug` (developer mode enables the Developer Tools plugin & extra assertions; `--debug` sets debug logging).

## Plugin class pattern
`ExamplePlugin extends net.runelite.client.plugins.Plugin`, annotated `@PluginDescriptor(name = "Example")` and `@Slf4j`; `@Inject Client client;` (`net.runelite.api.Client`), `@Inject ExampleConfig config;`; overrides `protected void startUp()` / `shutDown()`; event handlers via `@Subscribe` (`net.runelite.client.eventbus.Subscribe`), e.g. `onGameStateChanged(GameStateChanged)`; config provided via `@Provides ExampleConfig provideConfig(ConfigManager configManager) { return configManager.getConfig(ExampleConfig.class); }`. Config is an interface extending `net.runelite.client.config.Config` annotated `@ConfigGroup("example")` with `@ConfigItem(keyName, name, description)` default methods.

## runelite-plugin.properties fields (verbatim template)
```
displayName=Example
author=Nobody
description=An example greeter plugin
tags=
version=
plugins=com.example.ExamplePlugin
build=standard
```
- `tags`: comma-separated search keywords. `version`: optional; if empty the commit hash is used. `plugins`: comma-separated FQCNs (multiple allowed, rarely needed). `build`: `standard` or `gradle` â€” in `standard` mode the hub REPLACES your build.gradle/settings.gradle at packaging time (expedited review; use it unless you need extra dependencies/custom build steps).

## icon.png
Optional; file named `icon.png` at repository root; **no larger than 48x72 px**. Must be a real PNG (not renamed JPEG/ICO); keep it small â€” Java holds decoded images at width*height*4 bytes.

## Running/debugging locally on Windows
- `gradlew.bat run` from the plugin root, or in IntelliJ open build.gradle and click the green run triangle next to the `run` task (can also run/debug `ExamplePluginTest.main` directly with VM option `-ea` and args `--developer-mode --debug`).
- Jagex account login into the dev client: launcher >= 2.6.3; run "RuneLite (configure)" from the Windows Start menu, add `--insecure-write-credentials` to Client arguments, save, launch once via the Jagex launcher â€” it writes `.runelite/credentials.properties` (i.e. `%USERPROFILE%\.runelite\credentials.properties`); the IDE-launched client then logs in automatically. Delete the file when done; "End sessions" on runescape.com invalidates it.
- Never automate game input while testing â€” violates Jagex third-party rules.

## Hub submission process
1. Working public repo + BSD-2-Clause (or similarly permissive) LICENSE + README. 2. Fork `github.com/runelite/plugin-hub`, branch, add ONE file `plugins/<your-plugin-name>` containing exactly:
```
repository=https://github.com/<user>/<repo>.git
commit=<full 40-char commit hash>
```
3. Open PR against runelite/plugin-hub; CI (`.github/workflows/build.yml / build (pull_request)`) must go green, plus an automated "RuneLite Plugin Hub Checks" bot may request changes. Iterate by pushing new `commit=` hashes to the SAME PR. Updates later = new PR bumping `commit=` (recommended flow: branch from upstream/master, edit, force-push, `gh pr create -w`).
- New non-transitive dependencies require Gradle dependency-verification: add to the `thirdParty` configuration in `package/verification-template/build.gradle` in the plugin-hub repo, run `./gradlew --write-verification-metadata sha256`, and a maintainer manually verifies â€” this slows review substantially, so avoid new deps.
- Review rejects anything malicious, anything breaking Jagex third-party-client guidelines, anything on the Rejected/Rolled-Back Features wiki list, and anything too hard to verify ("If it is difficult for us to ensure the plugin isn't against the rules we will not merge it").

## Rules relevant to Material Checklist (from example-plugin AGENTS.md â€” official)
Forbidden: reflection, JNI/JNA, Unsafe/LWJGL, Process/ProcessBuilder, dynamic classloading/code download, runtime codegen, Java (de)serialization. Must use `net.runelite.api.gameval` constants (`ItemID`, `InterfaceID`, `ObjectID`) â€” the OLD `net.runelite.api.ItemID` constant location is superseded; never hardcode IDs. HTTP only via `@Inject OkHttpClient` (never on client thread; use `enqueue()`, hop back with `clientThread.invoke()`); JSON via `@Inject Gson` (derive with `.newBuilder()`); do NOT declare gson/guice/okhttp in build.gradle (they're runelite-client transitives). File I/O only under `RuneLite.RUNELITE_DIR` (`.runelite/<your-plugin>/`). Config group name must be specific (e.g. `material-checklist`), never renamed without migration. Rename EVERYTHING from the template (package, classes, group, rootProject.name, properties); no `META-INF/services` plugin file; don't commit build artifacts; `log.debug` for high-frequency logging; clean up overlays/listeners in `shutDown()`; no scene scans every tick â€” track via spawn events; keep per-frame Overlay work minimal. Our planned features (bank+inventory material tracking, recipe drill-down, Swing panel) hit none of the boss/PvP/menu/input restriction categories.

## Key facts
- Template repo: github.com/runelite/example-plugin (default branch master); generate a public repo from it or use create_new_plugin.py from github.com/runelite/plugin-hub-tooling
- runelite-client dependency: compileOnly + testImplementation 'net.runelite:client:latest.release' from maven repo https://repo.runelite.net (with content filter includeGroupByRegex("net\\.runelite.*")), plus mavenLocal() and mavenCentral(); template also adds testImplementation 'net.runelite:jshell:latest.release'
- Current released runelite-client version: 1.12.38 (repo.runelite.net maven-metadata.xml <release>, lastUpdated 20260903); but keep 'latest.release' in build.gradle â€” the hub expects it and it auto-tracks weekly client releases
- JDK 11 (Eclipse Temurin recommended); compile enforced via options.release.set(11); plugin-hub CI builds on JDK 11 (adopt) on ubuntu-24.04 â€” all plugin code must be Java 11 compatible
- Template ships Gradle wrapper 8.10 (gradle-8.10-all.zip, sha256 682b4df7fe5accdca84a4d1ef6a3a6ab096b3efd5edf7de2bd8c758d95a93703) with gradlew and gradlew.bat
- Dev-run pattern: src/test class with public static void main calling ExternalPluginManager.loadBuiltin(YourPlugin.class) (varargs, package net.runelite.client.externalplugins) then RuneLite.main(args); Gradle 'run' task = JavaExec on sourceSets.test.runtimeClasspath, jvmArgs "-ea", args "--developer-mode", "--debug"
- runelite-plugin.properties fields: displayName, author, description, tags (comma-separated), version (optional, defaults to commit hash), plugins (comma-separated FQCNs), build (standard|gradle); build=standard means the hub REPLACES your build.gradle/settings.gradle at packaging â€” fastest review path, use unless adding dependencies
- icon.png: optional, at repo root, max 48x72 px, must be a genuine PNG, keep dimensions small (decoded at w*h*4 bytes in memory)
- Windows local run: gradlew.bat run (or IntelliJ green arrow on the run task / debug ExamplePluginTest.main); Jagex-account login: launcher >=2.6.3, 'RuneLite (configure)' from Start menu, add --insecure-write-credentials client arg, launch once via Jagex launcher to write %USERPROFILE%\.runelite\credentials.properties, then the IDE client logs in automatically
- Hub submission: fork runelite/plugin-hub, add one file plugins/<name> with repository=<https .git url> and commit=<full 40-char sha>, open PR; iterate in the same PR by bumping commit=; updates are new PRs bumping commit=; CI build + 'RuneLite Plugin Hub Checks' bot must pass; review rejects Jagex-guideline violations and Rejected/Rolled-Back features
- Third-party (non-runelite-transitive) dependencies require SHA-256 dependency verification via a PR to plugin-hub's package/verification-template/build.gradle thirdParty configuration + manual maintainer verification â€” strongly discouraged; gson, guice, okhttp are runelite-client transitives and must NOT be added to build.gradle (use @Inject OkHttpClient and @Inject Gson)
- Forbidden in hub plugins: reflection, JNI/JNA, Unsafe/LWJGL, Process/ProcessBuilder, downloading/dynamically loading code, runtime code generation, Java (de)serialization, injecting mouse/keyboard input, autotyping, exposing player info over HTTP, crowdsourcing other-player data; also category bans on boss-helper, PvP-helper, menu-modification and interface-unhiding features (none affect a materials-checklist plugin)
- Current API practice (from official example-plugin AGENTS.md): use net.runelite.api.gameval constants (ItemID, InterfaceID, ObjectID) instead of legacy net.runelite.api.* constant classes; pass full component IDs like client.getWidget(InterfaceID.Xxx.YYY); use LinkBrowser for URLs; file I/O only under RuneLite.RUNELITE_DIR subdirectory
- example-plugin now contains AGENTS.md (and CLAUDE.md pointing to it) with official RuneLite agent guidelines: log.debug for frequent logs, no Thread.sleep, no blocking on client thread, cancel ScheduledFutures in shutDown, don't scan scene every tick, config group names must be specific and never renamed without migration, third-party-server features must be opt-in (disabled by default) with the exact warning string about submitting IP to a 3rd-party server
- Hub plugin resources go in src/main/resources and MUST be read with Class.getResourceAsStream() (never getResource()) because hub plugins are distributed as jars on the classpath and never unpacked to disk
- Licensing: hub README walks through adding BSD 2-Clause 'Simplified' License via GitHub UI; AGENTS.md says retain a permissive license such as BSD-2
- Template plugin skeleton: Plugin subclass with @PluginDescriptor(name=...), @Slf4j, @Inject Client, @Inject config, startUp()/shutDown() overrides, @Subscribe event methods, and @Provides ConfigManager.getConfig(...) provider; Config is an interface extending net.runelite.client.config.Config with @ConfigGroup + @ConfigItem default methods

## Code patterns
// ExamplePluginTest.java (src/test/java/com/example/) â€” verbatim from raw.githubusercontent.com/runelite/example-plugin/master
package com.example;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
public class ExamplePluginTest {
    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(ExamplePlugin.class);
        RuneLite.main(args);
    }
}

// build.gradle key blocks â€” verbatim from example-plugin/master/build.gradle
def runeLiteVersion = 'latest.release'
def pluginMainClass = 'com.example.ExamplePluginTest'
dependencies {
    compileOnly group: 'net.runelite', name:'client', version: runeLiteVersion
    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'
    testImplementation 'junit:junit:4.12'
    testImplementation group: 'net.runelite', name:'client', version: runeLiteVersion
    testImplementation group: 'net.runelite', name:'jshell', version: runeLiteVersion
}
tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release.set(11) }
tasks.register('run', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = pluginMainClass
    jvmArgs "-ea"
    args "--developer-mode", "--debug"
}
// repositories: mavenLocal(); maven { url = 'https://repo.runelite.net'; content { includeGroupByRegex("net\\.runelite.*") } }; mavenCentral()
// also a 'shadowJar' Jar task bundling main+test outputs with Main-Class = pluginMainClass, excluding META-INF signatures and module-info.class

// Plugin skeleton â€” verbatim from example-plugin/master/src/main/java/com/example/ExamplePlugin.java
@Slf4j
@PluginDescriptor(name = "Example")
public class ExamplePlugin extends Plugin {
    @Inject private Client client;               // net.runelite.api.Client
    @Inject private ExampleConfig config;
    @Override protected void startUp() throws Exception { log.debug("Example started!"); }
    @Override protected void shutDown() throws Exception { log.debug("Example stopped!"); }
    @Subscribe public void onGameStateChanged(GameStateChanged e) { /* net.runelite.client.eventbus.Subscribe */ }
    @Provides ExampleConfig provideConfig(ConfigManager configManager) { return configManager.getConfig(ExampleConfig.class); }
}

// Config skeleton â€” verbatim from ExampleConfig.java
@ConfigGroup("example")
public interface ExampleConfig extends Config {
    @ConfigItem(keyName = "greeting", name = "Welcome Greeting", description = "...")
    default String greeting() { return "Hello"; }
}

// runelite-plugin.properties â€” verbatim
displayName=Example
author=Nobody
description=An example greeter plugin
tags=
version=
plugins=com.example.ExamplePlugin
build=standard

// plugin-hub submission manifest: file plugins/<plugin-name> in fork of runelite/plugin-hub
repository=https://github.com/dekvall/helmet-check.git
commit=9db374fc205c5aae1f99bd5fd127266076f40ec8

// Widget lookup per current AGENTS.md guidance (gameval constants):
client.getWidget(InterfaceID.DomEndLevelUi.LOOT_VALUE)   // net.runelite.api.gameval.InterfaceID

## Risks
- 'latest.release' is a Gradle dynamic version â€” local builds can silently jump to a new client (weekly releases; 1.12.38 today, 1.12.39-SNAPSHOT pending). API breakage after a RuneLite update is fixed by refreshing Gradle dependencies, but pin nothing: the hub rebuilds against its own runelite.version anyway
- Item/constant API churn is real and confirmed: the official AGENTS.md now mandates net.runelite.api.gameval.ItemID/InterfaceID/ObjectID; code written against memory of net.runelite.api.ItemID or old widget ID composition will draw review flak (another research dimension should verify gameval specifics and InventoryID changes)
- build=standard replaces build.gradle/settings.gradle at hub packaging time â€” anything custom you add to build.gradle (extra deps, resource processing) will NOT exist in the hub build unless you switch to build=gradle, which slows review
- Any dependency beyond runelite-client transitives requires a separate verification PR (sha256 metadata + manual maintainer check) and 'significantly' longer review â€” design Material Checklist to use only bundled libs (Gson, OkHttp, Guava, Guice, Lombok compile-only)
- The example-plugin test deps (junit 4.12) and lombok 1.18.30 are what the template pins today; they change occasionally â€” re-check the template at scaffold time rather than copying this snapshot months later
- Hub review has a hard bot gate ('RuneLite Plugin Hub Checks') plus human review; timeline is 'be patient' â€” plan for days-to-weeks between submission and availability
- Resource loading behaves differently in IDE (file URL) vs hub (jar URL): only Class.getResourceAsStream() is safe; getResource()-based code will pass local testing and break in production
- Jagex-account dev login writes plaintext-equivalent credentials to %USERPROFILE%\.runelite\credentials.properties via --insecure-write-credentials â€” must not be committed or shared; delete after development
- Wiki page content (Using Jagex Accounts) was summarized via fetch, not read raw; exact UI wording may drift, but flag name --insecure-write-credentials and launcher >=2.6.3 requirement are as stated on the wiki today

## Sources
- https://raw.githubusercontent.com/runelite/example-plugin/master/build.gradle
- https://raw.githubusercontent.com/runelite/example-plugin/master/settings.gradle
- https://raw.githubusercontent.com/runelite/example-plugin/master/runelite-plugin.properties
- https://raw.githubusercontent.com/runelite/example-plugin/master/gradle/wrapper/gradle-wrapper.properties
- https://raw.githubusercontent.com/runelite/example-plugin/master/src/test/java/com/example/ExamplePluginTest.java
- https://raw.githubusercontent.com/runelite/example-plugin/master/src/main/java/com/example/ExamplePlugin.java
- https://raw.githubusercontent.com/runelite/example-plugin/master/src/main/java/com/example/ExampleConfig.java
- https://raw.githubusercontent.com/runelite/example-plugin/master/AGENTS.md
- https://raw.githubusercontent.com/runelite/example-plugin/master/CLAUDE.md
- https://raw.githubusercontent.com/runelite/example-plugin/master/.gitignore
- https://api.github.com/repos/runelite/example-plugin/git/trees/master?recursive=1
- https://raw.githubusercontent.com/runelite/plugin-hub/master/README.md
- https://raw.githubusercontent.com/runelite/plugin-hub/master/.github/workflows/build.yml
- https://repo.runelite.net/net/runelite/client/maven-metadata.xml
- https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
