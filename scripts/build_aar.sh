#!/bin/bash
# run this from the project root or the scripts directory

# 1. Exit immediately if a command fails
set -e

# 2. Navigate to the mobile directory (assumes script is in vaydns-vpn/scripts)
# Adjust this based on where you store this .sh file
cd "$(dirname "$0")/../mobile" 

echo "Cleaning old builds..."
rm -f ../vaydns-arm64.aar
rm -f ../android/app/libs/vaydns-arm64.aar

echo "Building for Android ARM64 (Clean Developer Build)..."
gomobile bind -v \
  -target=android/arm64 \
  -androidapi 24 \
  -ldflags="-s -w" \
  -trimpath \
  -o ../vaydns-arm64.aar .

echo "Moving AAR to Android project..."
mkdir -p ../android/app/libs/
cp ../vaydns-arm64.aar ../android/app/libs/

echo "------------------------------------------------"
echo "✅ Build Complete: vaydns-arm64.aar"
echo "📍 Location: android/app/libs/"
echo "ℹ️  Note: This build is UNVERIFIED (No keys injected)."
echo "------------------------------------------------"
