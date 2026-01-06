#!/bin/bash
# Build script for Rust library for iOS
# Run this before building the Xcode project when Rust code changes

set -e

cd "$(dirname "$0")/../.."

echo "Building spweeboard-core for iOS..."

# Build for iOS Simulator (arm64)
echo "→ Building for iOS Simulator (arm64)..."
cargo build -p spweeboard-core --features uniffi --target aarch64-apple-ios-sim --release

# Build for iOS Device (arm64)
echo "→ Building for iOS Device (arm64)..."
cargo build -p spweeboard-core --features uniffi --target aarch64-apple-ios --release

# Generate Swift bindings
echo "→ Generating Swift bindings..."
cargo run -p spweeboard-core --features uniffi --bin uniffi-bindgen -- \
    generate --library target/aarch64-apple-ios-sim/release/libspweeboard_core.dylib \
    --language swift \
    --out-dir platforms/ios/Spweeboard/Generated

# Copy libraries to Xcode project
echo "→ Copying libraries..."
mkdir -p platforms/ios/Spweeboard/SpweeboardCore

# For simulator
cp target/aarch64-apple-ios-sim/release/libspweeboard_core.a \
   platforms/ios/Spweeboard/SpweeboardCore/libspweeboard_core_sim.a

# For device
cp target/aarch64-apple-ios/release/libspweeboard_core.a \
   platforms/ios/Spweeboard/SpweeboardCore/libspweeboard_core_device.a

# Copy headers and Swift bindings
cp platforms/ios/Spweeboard/Generated/spweeboard_coreFFI.h \
   platforms/ios/Spweeboard/SpweeboardCore/
cp platforms/ios/Spweeboard/Generated/spweeboard_coreFFI.modulemap \
   platforms/ios/Spweeboard/SpweeboardCore/module.modulemap
cp platforms/ios/Spweeboard/Generated/spweeboard_core.swift \
   platforms/ios/Spweeboard/SpweeboardCore/

echo "✓ Build complete!"
echo ""
echo "Add to Xcode project:"
echo "1. Add SpweeboardCore folder to both Spweeboard and SpweeboardKeyboard targets"
echo "2. In Build Settings:"
echo "   - Add \$(PROJECT_DIR)/SpweeboardCore to 'Swift Compiler - Search Paths > Import Paths'"
echo "   - Add \$(PROJECT_DIR)/SpweeboardCore to 'Library Search Paths'"
echo "3. In Build Phases > Link Binary With Libraries:"
echo "   - Add libspweeboard_core_sim.a (for Simulator)"
echo "   - Add libspweeboard_core_device.a (for Device)"
