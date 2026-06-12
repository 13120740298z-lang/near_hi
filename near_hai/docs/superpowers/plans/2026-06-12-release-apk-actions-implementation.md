# Release APK Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a GitHub Actions pipeline that builds a signed Android release APK, uploads it as an artifact, and documents the required signing secrets.

**Architecture:** The implementation keeps signing material out of the repository by decoding a Base64 keystore from GitHub Secrets during the workflow run. Gradle reads signing inputs from environment variables only for release builds, while debug builds stay unchanged.

**Tech Stack:** GitHub Actions, Android Gradle Plugin 8.2, Gradle Kotlin DSL, Java 17, Kotlin Android

---

### File Map

- Create: `.github/workflows/android-release-apk.yml`
- Create: `docs/android-release-apk-secrets.md`
- Modify: `app/build.gradle.kts`

### Task 1: Add Release Signing Config To Gradle

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add a failing release-signing guard**

Insert the following block near the top of `app/build.gradle.kts`, after `plugins { ... }` and before `android { ... }`:

```kotlin
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

val requestedTasks = gradle.startParameter.taskNames.joinToString(" ").lowercase()
val isReleaseTaskRequested = requestedTasks.contains("release")

if (isReleaseTaskRequested) {
    val missingVars = buildList {
        if (releaseKeystorePath.isNullOrBlank()) add("ANDROID_KEYSTORE_PATH")
        if (releaseKeystorePassword.isNullOrBlank()) add("ANDROID_KEYSTORE_PASSWORD")
        if (releaseKeyAlias.isNullOrBlank()) add("ANDROID_KEY_ALIAS")
        if (releaseKeyPassword.isNullOrBlank()) add("ANDROID_KEY_PASSWORD")
    }

    check(missingVars.isEmpty()) {
        "Missing release signing environment variables: ${missingVars.joinToString(", ")}"
    }
}
```

Reason: this makes `assembleRelease` fail immediately with a direct message when signing inputs are missing.

- [ ] **Step 2: Run release task to verify the guard fails**

Run:

```bash
./gradlew assembleRelease
```

Expected: FAIL with an error containing `Missing release signing environment variables`.

- [ ] **Step 3: Add the minimal signing configuration**

Replace the current `android { ... }` block with the following structure, preserving existing SDK, build features, and dependencies:

```kotlin
android {
    namespace = "com.tapbump.chat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tapbump.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystorePath.isNullOrBlank()) {
                storeFile = file(releaseKeystorePath)
            }
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}
```

Reason: this binds the release build to CI-provided signing values without changing debug behavior.

- [ ] **Step 4: Run debug task to verify debug builds still work without secrets**

Run:

```bash
./gradlew assembleDebug
```

Expected: PASS and produce a debug APK without requiring any signing environment variables.

- [ ] **Step 5: Commit the Gradle change**

Run:

```bash
git add app/build.gradle.kts
git commit -m "build: add env-based release signing"
```

Expected: one commit containing only the Gradle signing change.

### Task 2: Add The GitHub Actions Release Workflow

**Files:**
- Create: `.github/workflows/android-release-apk.yml`

- [ ] **Step 1: Add the workflow file**

Create `.github/workflows/android-release-apk.yml` with this content:

```yaml
name: Android Release APK

on:
  workflow_dispatch:
  push:
    branches:
      - main
      - master

jobs:
  build-release-apk:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
          cache: gradle

      - name: Validate signing secrets
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          missing=""
          [ -n "$ANDROID_KEYSTORE_BASE64" ] || missing="$missing ANDROID_KEYSTORE_BASE64"
          [ -n "$ANDROID_KEYSTORE_PASSWORD" ] || missing="$missing ANDROID_KEYSTORE_PASSWORD"
          [ -n "$ANDROID_KEY_ALIAS" ] || missing="$missing ANDROID_KEY_ALIAS"
          [ -n "$ANDROID_KEY_PASSWORD" ] || missing="$missing ANDROID_KEY_PASSWORD"
          if [ -n "$missing" ]; then
            echo "Missing required GitHub Secrets:$missing" >&2
            exit 1
          fi

      - name: Decode release keystore
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          echo "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$RUNNER_TEMP/release-keystore.jks"
          test -s "$RUNNER_TEMP/release-keystore.jks"

      - name: Make Gradle executable
        run: chmod +x ./gradlew

      - name: Build release APK
        env:
          ANDROID_KEYSTORE_PATH: ${{ runner.temp }}/release-keystore.jks
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: ./gradlew assembleRelease

      - name: Upload release APK
        uses: actions/upload-artifact@v4
        with:
          name: app-release-apk
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error
```

Reason: this creates the end-to-end CI path from checkout to signed APK artifact upload.

- [ ] **Step 2: Validate workflow syntax locally**

Run:

```bash
python - <<'PY'
from pathlib import Path

text = Path(".github/workflows/android-release-apk.yml").read_text()
required = [
    "name: Android Release APK",
    "build-release-apk:",
    "run: ./gradlew assembleRelease",
    "uses: actions/upload-artifact@v4",
    "path: app/build/outputs/apk/release/*.apk",
]
missing = [item for item in required if item not in text]
assert not missing, missing
print("workflow-ok")
PY
```

Expected: PASS with output `workflow-ok`.

- [ ] **Step 3: Record the manual CI verification command**

Run:

```bash
git diff -- .github/workflows/android-release-apk.yml
```

Expected: show the new workflow file with the `assembleRelease` step and artifact upload path.

- [ ] **Step 4: Commit the workflow**

Run:

```bash
git add .github/workflows/android-release-apk.yml
git commit -m "ci: add android release apk workflow"
```

Expected: one commit containing only the workflow file.

### Task 3: Document Required GitHub Secrets And Verification Steps

**Files:**
- Create: `docs/android-release-apk-secrets.md`

- [ ] **Step 1: Add the operator documentation**

Create `docs/android-release-apk-secrets.md` with this content:

```md
# Android Release APK Secrets

## Required GitHub Secrets

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded content of the release keystore file
- `ANDROID_KEYSTORE_PASSWORD`: Password for the keystore
- `ANDROID_KEY_ALIAS`: Alias of the signing key
- `ANDROID_KEY_PASSWORD`: Password for the signing key

## How To Generate Base64

Run one of the following commands locally and copy the output into the `ANDROID_KEYSTORE_BASE64` GitHub secret.

### Linux

```bash
base64 -w 0 release-keystore.jks
```

### macOS

```bash
base64 -i release-keystore.jks
```

## How To Trigger The Workflow

1. Open GitHub Actions
2. Select `Android Release APK`
3. Click `Run workflow`
4. Download the `app-release-apk` artifact after the job succeeds

## Failure Guide

- `Missing required GitHub Secrets`: one or more repository secrets were not configured
- `Missing release signing environment variables`: the workflow did not pass signing values into Gradle
- Signing failure during `assembleRelease`: the keystore password, key alias, or key password is wrong
```

Reason: this gives the repository operator a single source of truth for configuring and debugging release builds.

- [ ] **Step 2: Verify the documentation covers all required secrets**

Run:

```bash
python - <<'PY'
from pathlib import Path

text = Path("docs/android-release-apk-secrets.md").read_text()
required = [
    "ANDROID_KEYSTORE_BASE64",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
]
missing = [name for name in required if name not in text]
assert not missing, missing
print("docs-ok")
PY
```

Expected: PASS with output `docs-ok`.

- [ ] **Step 3: Capture the final repository state for review**

Run:

```bash
git status --short
```

Expected: show only the planned workflow, Gradle, and documentation changes before the final commit.

- [ ] **Step 4: Commit the docs change**

Run:

```bash
git add docs/android-release-apk-secrets.md
git commit -m "docs: add release apk secrets guide"
```

Expected: one commit containing only the operator documentation.

### Task 4: Final Validation

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `.github/workflows/android-release-apk.yml`
- Create: `docs/android-release-apk-secrets.md`

- [ ] **Step 1: Review the full diff**

Run:

```bash
git diff HEAD~3..HEAD -- app/build.gradle.kts .github/workflows/android-release-apk.yml docs/android-release-apk-secrets.md
```

Expected: show the exact three deliverables and no unrelated file changes.

- [ ] **Step 2: Run the local non-secret validation commands**

Run:

```bash
./gradlew assembleDebug && python - <<'PY'
from pathlib import Path

workflow = Path(".github/workflows/android-release-apk.yml")
assert "run: ./gradlew assembleRelease" in workflow.read_text()
print("local-validation-ok")
PY
```

Expected: PASS with output ending in `local-validation-ok`.

- [ ] **Step 3: Trigger CI after secrets are configured**

Run after pushing the branch:

```bash
git push origin HEAD
```

Expected: the `Android Release APK` workflow becomes available in GitHub Actions and can be started with `Run workflow`.

- [ ] **Step 4: Verify the release artifact in GitHub**

Manual check in GitHub Actions:

```text
Open the successful workflow run and confirm the artifact named "app-release-apk" is downloadable.
```

Expected: the uploaded artifact contains the release APK from `app/build/outputs/apk/release/`.

- [ ] **Step 5: Create the final integration commit if any validation fix was needed**

Run only if Task 4 required follow-up edits:

```bash
git add app/build.gradle.kts .github/workflows/android-release-apk.yml docs/android-release-apk-secrets.md
git commit -m "chore: finalize release apk pipeline"
```

Expected: no extra commit is needed if the first three task commits already pass validation.
