#!/bin/bash

# Clean previous builds
rm -rf vaydns-arm64.aar vaydns-arm64-sources.jar

# Build for Android ARM64 with size optimizations
gomobile bind -v \
    -target=android/arm64 \
    -ldflags="-s -w" \
    -androidapi 24 \
    -o vaydns-arm64.aar \
    ./mobile

echo "Build Complete: vaydns-arm64.aar created (Optimized for ARM64)"
