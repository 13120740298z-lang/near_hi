# GitHub Actions Release APK Design

## Background

This repository is a standard Android application project using:

- Android Gradle Plugin `8.2.0`
- Kotlin Android plugin `1.9.20`
- Gradle wrapper `8.5`
- Java 17 source and target compatibility

The project currently contains the application module and Gradle build files, but it does not yet contain a complete GitHub Actions workflow for producing a signed release APK. The user goal is to make GitHub Actions build an installable release APK rather than only relying on local packaging.

## Goal

Create a reliable CI pipeline that:

1. Builds a signed release APK in GitHub Actions
2. Uploads the generated APK as a workflow artifact
3. Fails clearly when signing material is missing or invalid
4. Keeps local development usable without forcing secrets into the repository

## Non-Goals

- Building an Android App Bundle (`.aab`)
- Publishing to Google Play
- Automatic version bumping
- Multi-flavor or multi-module release orchestration

## Current Constraints

- The app is configured as a single Android application module: `:app`
- `release` build type exists, but no CI-oriented signing configuration is defined yet
- The repository does not currently include a working workflow under `.github/workflows/`
- The availability of the release keystore is not yet confirmed, so the solution must make the required inputs explicit

## Recommended Approach

Use a standard GitHub Actions workflow on `ubuntu-latest` that restores a Base64-encoded keystore from repository secrets, exports signing values as environment variables, runs `./gradlew assembleRelease`, and uploads the generated APK artifact.

This is the recommended approach because it:

- Works with standard GitHub-hosted runners
- Avoids storing the binary keystore file in the repository
- Keeps the signing configuration explicit and auditable
- Matches common Android CI/CD practices with minimal project-specific complexity

## Alternative Approaches Considered

### Option A: Build only debug APK in CI

Pros:

- Fastest path to a green workflow
- Requires no keystore or signing secrets

Cons:

- Does not satisfy the requirement to produce a release APK
- Does not validate release signing and packaging logic

### Option B: Conditional CI fallback to debug when release secrets are missing

Pros:

- More forgiving while bootstrapping CI
- Keeps workflow runnable before all release secrets are ready

Cons:

- Can hide release readiness problems
- Produces ambiguous workflow outcomes
- Increases workflow logic complexity

### Option C: Recommended fixed release pipeline

Pros:

- Clear success criteria
- Clear failure conditions
- Directly solves the requested problem

Cons:

- Requires the user to prepare signing material up front

## Design

### 1. Workflow File

Add a workflow file at `.github/workflows/android-release-apk.yml`.

The workflow should:

1. Trigger on push to selected branches and optionally on manual dispatch
2. Check out the repository
3. Set up JDK 17
4. Set up Android build caching through Gradle
5. Restore the keystore from a Base64 secret into a temporary file
6. Export signing environment variables for Gradle
7. Run `./gradlew assembleRelease`
8. Upload `app/build/outputs/apk/release/*.apk` as an artifact

### 2. Secrets Contract

The workflow expects the following GitHub repository secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64 content of the keystore file
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: key alias inside the keystore
- `ANDROID_KEY_PASSWORD`: key password for the alias

Optional future variables may include versioning or release metadata, but they are not needed for the first working release APK pipeline.

### 3. Gradle Signing Configuration

Update `app/build.gradle.kts` to define a `signingConfigs.release` block that reads from environment variables, for example:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then bind that signing config to `buildTypes.release`.

The release build should fail early with a clear error if one or more required values are missing. This prevents confusing late-stage signing failures and makes CI errors actionable.

### 4. Local and CI Behavior

The design should keep local development practical:

- Local debug builds continue to work without any release secrets
- Release builds require explicit signing values
- CI sets those values through workflow environment configuration

If a local developer wants to build a release APK, they can export the same environment variables before invoking Gradle.

### 5. Artifact Strategy

The workflow uploads the generated APK as a GitHub Actions artifact so it can be downloaded from the workflow page.

The initial artifact scope is:

- `app-release.apk` or equivalent output under `app/build/outputs/apk/release/`

No release asset publishing step is included in the first version.

## Data Flow

1. GitHub Actions starts the workflow
2. Secrets are injected into the runner environment
3. The Base64 keystore value is decoded into a temporary file path
4. Environment variables are passed to Gradle
5. Gradle configures the release signing config
6. `assembleRelease` produces the signed APK
7. The APK is uploaded as a build artifact

## Error Handling

The implementation should explicitly handle these failure modes:

1. Missing secret values
   - Fail before invoking Gradle or at Gradle configuration time with a direct message about which values are missing
2. Invalid Base64 keystore content
   - Fail in the decode step before the Gradle build begins
3. Wrong keystore password, alias, or key password
   - Fail during signing with the normal Gradle signing error
4. APK path mismatch during upload
   - Keep the upload path aligned with the standard Android output directory and verify it after the first successful run

## Testing and Validation

Validation should cover:

1. Static validation
   - Confirm the workflow references Java 17 and the expected Gradle command
   - Confirm the Gradle file binds `release` to a signing config
2. Local validation
   - If signing material is available locally, run `./gradlew assembleRelease`
3. CI validation
   - Trigger the workflow manually
   - Confirm the job completes successfully
   - Confirm the release APK artifact appears in GitHub Actions

## Implementation Checklist

1. Add `.github/workflows/android-release-apk.yml`
2. Update `app/build.gradle.kts` with release signing config from environment variables
3. Keep debug builds unchanged
4. Document required GitHub secrets
5. Run validation for workflow syntax and Gradle build behavior

## Acceptance Criteria

The design is complete when all of the following are true:

- A workflow exists in `.github/workflows/`
- The workflow can build `assembleRelease`
- The release build reads signing config from environment variables
- A successful workflow run uploads a release APK artifact
- Failures caused by missing signing inputs are understandable without inspecting repository code

## Open Dependency From User

The user must prepare or confirm a release keystore and provide the required signing values in GitHub repository secrets. Without that material, a real signed release APK cannot be produced in GitHub Actions.
