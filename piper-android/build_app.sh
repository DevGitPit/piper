#!/bin/bash
set -e

# AAPT2 Fix for Termux
export LD_PRELOAD=/data/data/com.termux/files/home/libaapt2_fix.so

echo "Building Android app..."
gradle assembleDebug

echo "App build successful."
