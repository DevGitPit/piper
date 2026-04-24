# Piper Android Application

## Project Overview
This directory contains the **Android application** for Piper, a high-performance, on-device text-to-speech (TTS) system. It demonstrates how to integrate the Piper ONNX models into a mobile app using Kotlin and JNI.

The application serves two main purposes:
1.  **Standalone TTS Player:** A user-friendly interface to type/paste text, select voices, and generate speech.
2.  **System TTS Service:** Implements the Android `TextToSpeechService` API, allowing Piper to be used as the system-wide TTS engine.

## Key Features
*   **Offline Inference:** Runs entirely on-device using ONNX Runtime.
*   **Multilingual Support:** Supports multiple languages as defined by the models.
*   **Native Rust Core:** Uses a Rust backend (`piper_tts`) accessed via JNI for high-performance inference.
*   **Material Design 3:** Modern UI built with Jetpack Compose.
*   **System Integration:** Works as a standard Android TTS provider.
*   **Audio Export:** Save synthesized speech as WAV files.
*   **Lexicon Management:** User-defined pronunciation rules.
*   **History & Queue:** Track and manage synthesis requests.

## Architecture

### Directory Structure
*   `app/src/main/java/com.brahmadeo.piper.tts/`: Kotlin source code.
    *   `MainActivity.kt`, `PlaybackActivity.kt`, `QueueActivity.kt`: Key entry points.
    *   `PiperTTS.kt`: JNI wrapper class for the native Rust library.
    *   `ui/`: Jetpack Compose screens (`MainScreen`, `PlaybackScreen`, `HistoryScreen`, `LexiconScreen`, `QueueScreen`, `SavedAudioScreen`).
    *   `viewmodel/`: `MainViewModel.kt` for UI state management.
    *   `service/`:
        *   `PlaybackService.kt`: Foreground service handling audio playback.
        *   `PiperTextToSpeechService.kt`: System TTS service implementation.
    *   `utils/`: Helpers for text normalization, language detection, and file management (`TextNormalizer`, `LanguageDetector`, `WavUtils`, `AssetManager`, `QueueManager`).
*   `rust/`: Native Rust/JNI bridge.
    *   `src/lib.rs`: Main JNI interface (init, synthesize, thermal management).
    *   `src/helper.rs`: TTS engine logic.
    *   `src/thermal.rs`: Thermal management.
    *   `vendor/`: Vendored Rust dependencies for offline builds.
    *   `.cargo/config.toml`: Cargo configuration (potentially modified by build scripts).
*   `metadata/`: F-Droid build metadata.
    *   `com.brahmadeo.piper.tts.yml`: Build recipe and metadata.

### Native Bridge
The app uses `PiperTTS.kt` to communicate with the compiled Rust library (`libpiper_tts.so`).
*   **Initialization:** Loads ONNX models and configures the engine.
*   **Synthesis:** Generates PCM audio data from text.
*   **Thermal Management:** Monitors device state to adjust performance.

### Build Configuration
*   **Gradle:** `app/build.gradle` manages the Android build and triggers the Cargo build for the Rust library.
    *   **Plugin:** `org.mozilla.rust-android-gradle.rust-android` handles Rust compilation.
    *   **Tasks:** `extractOnnxLib` (prepares `libonnxruntime.so`), `copyRustLibs` (packages native libs).
*   **Rust:** `rust/Cargo.toml` defines dependencies (ort, ndarray, rayon, etc.).
*   **F-Droid:** The project is configured for F-Droid with offline dependency vendoring (`rust/vendor`).

## Building and Running

### Prerequisites
*   **JDK 17** (required by `app/build.gradle` options).
*   **Android SDK** (API 35).
*   **NDK** (Version 26.1.10909125 or similar).
*   **Rust Toolchain** (via `rustup`, targets: `aarch64-linux-android`, `x86_64-linux-android`, etc.).
*   **Git LFS** (Critical for `assets/` models).

### Build Process
1.  **Initialize Rust Targets:**
    ```bash
    rustup target add aarch64-linux-android
    ```
2.  **Build APK:**
    ```bash
    ./gradlew assembleDebug
    ```
    (Note: Ensure `local.properties` points to your SDK and NDK).

### F-Droid Build Notes
*   **Metadata:** `metadata/com.brahmadeo.piper.tts.yml`
*   **Offline Mode:** `rust/.cargo/config.toml` should be configured to use `vendor/` source replacement for reproducible, offline builds.
*   **Linker:** The F-Droid metadata might inject specific linker configurations (e.g., `aarch64-linux-android34-clang`).

## Known Issues
*   **F-Droid Build Failure:** Discrepancies between the local environment and F-Droid's build server (linker versions, NDK paths, offline vendoring) can cause failures.
*   **Metadata Mismatch:** The current code (`v2.0.0-alpha.1`+) might be ahead of the `v1.1` tag defined in `metadata/com.brahmadeo.piper.tts.yml`.

## Development
*   **Namespace:** `com.brahmadeo.piper.tts`
*   **Min SDK:** 24
*   **Target SDK:** 35
*   **Compile SDK:** 35
