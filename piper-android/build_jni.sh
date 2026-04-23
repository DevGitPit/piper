#!/bin/bash
set -e

# Path to ONNX Runtime header
ORT_HEADER="/data/data/com.termux/files/home/onnxruntime/include/onnxruntime/core/session/onnxruntime_c_api.h"

echo "Patching ORT header..."
sed -i 's/#define ORT_API_VERSION 26/#define ORT_API_VERSION 25/' "$ORT_HEADER"

echo "Building Rust library..."
cd rust
cargo build --release

echo "Copying libraries to jniLibs..."
DEST="../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
cp target/release/libpiper_tts.so "$DEST"/
# Ensure the base onnxruntime lib is also there
cp ../../cpp_bench/libonnxruntime.so "$DEST"/

# Transitive deps
LIBS=(
    "/data/data/com.termux/files/usr/lib/libc++_shared.so"
    "/data/data/com.termux/files/usr/lib/libespeak-ng.so"
    "/data/data/com.termux/files/usr/lib/libpcaudio.so"
    "/data/data/com.termux/files/usr/lib/libpulse.so"
    "/data/data/com.termux/files/usr/lib/libpulse-simple.so"
    "/data/data/com.termux/files/usr/lib/pulseaudio/libpulsecommon-17.0.so"
    "/data/data/com.termux/files/usr/lib/libiconv.so"
    "/data/data/com.termux/files/usr/lib/libandroid-execinfo.so"
    "/data/data/com.termux/files/usr/lib/libFLAC.so"
    "/data/data/com.termux/files/usr/lib/libopus.so"
    "/data/data/com.termux/files/usr/lib/libmp3lame.so"
    "/data/data/com.termux/files/usr/lib/libvorbisenc.so"
    "/data/data/com.termux/files/usr/lib/libvorbis.so"
    "/data/data/com.termux/files/usr/lib/libogg.so"
    "/data/data/com.termux/files/usr/lib/libsndfile.so"
    "/data/data/com.termux/files/usr/lib/libdbus-1.so"
    "/data/data/com.termux/files/usr/lib/libgcrypt.so"
    "/data/data/com.termux/files/usr/lib/libgpg-error.so"
    "/data/data/com.termux/files/usr/lib/liblzma.so"
    "/data/data/com.termux/files/usr/lib/libzstd.so"
    "/data/data/com.termux/files/usr/lib/liblz4.so"
)

for lib in "${LIBS[@]}"; do
    if [ -f "$lib" ]; then
        cp "$lib" "$DEST"/
    else
        echo "Warning: $lib not found"
    fi
done

echo "Restoring ORT header..."
sed -i 's/#define ORT_API_VERSION 25/#define ORT_API_VERSION 26/' "$ORT_HEADER"

echo "JNI build successful."
