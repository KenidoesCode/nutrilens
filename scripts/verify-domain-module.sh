#!/usr/bin/env bash
# Compile and unit-test the pure-Kotlin domain module (android/core/model)
# without the Android SDK.
#
# Why this exists: the domain module is plain JVM Kotlin with no Android
# dependency, so it can be verified anywhere Gradle and a JDK are available --
# including CI runners and sandboxes that have no Android SDK. It is a genuine
# compile-and-test of the chrononutrition rules, the meal model and the error
# taxonomy, not a substitute for the full Android build.
#
# The full app build is:  cd android && ./gradlew test assembleDebug
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$REPO_ROOT/android/core/model"
WORK="${TMPDIR:-/tmp}/nutrilens-domain-verify"

if [[ ! -d "$MODULE/src/main/kotlin" ]]; then
  echo "error: $MODULE/src/main/kotlin not found" >&2
  exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK"

cat > "$WORK/settings.gradle.kts" <<'SETTINGS'
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "nutrilens-domain-verify"
SETTINGS

cat > "$WORK/build.gradle.kts" <<BUILD
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

sourceSets {
    main { kotlin.srcDirs("$MODULE/src/main/kotlin") }
    test { kotlin.srcDirs("$MODULE/src/test/kotlin") }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { testLogging { events("failed") } }
BUILD

GRADLE_BIN="${GRADLE_BIN:-gradle}"
"$GRADLE_BIN" -p "$WORK" test --no-configuration-cache --console=plain "$@"

python3 - "$WORK" <<'PY'
import glob, re, sys, xml.etree.ElementTree as ET

total = failures = 0
for path in glob.glob(f"{sys.argv[1]}/build/test-results/test/*.xml"):
    suite = ET.parse(path).getroot()
    total += int(suite.get("tests", 0))
    failures += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
print(f"\ndomain module: {total} tests, {failures} failures")
sys.exit(1 if failures else 0)
PY
