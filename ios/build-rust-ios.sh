#!/usr/bin/env bash
# Compila vintage_core (Rust) para iPhone y simulador, genera los bindings
# Swift con UniFFI y arma VintageCore.xcframework en ios/Generated/.
# Requiere macOS con Xcode y Rust. Uso: ./ios/build-rust-ios.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
CRATE="$ROOT/vintage_core"
OUT="$ROOT/ios/Generated"
export IPHONEOS_DEPLOYMENT_TARGET=16.0

rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

cd "$CRATE"
for t in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
  cargo build --release --lib --target "$t"
done

# Bindings Swift (desde una compilación del host sin strip)
cargo build --features cli
rm -rf "$OUT" && mkdir -p "$OUT/headers"
./target/debug/uniffi-bindgen generate --library target/debug/libvintage_core.dylib \
  --language swift --no-format --out-dir "$OUT/bindings"
mv "$OUT/bindings/vintage_core.swift" "$OUT/vintage_core.swift"
cp "$OUT/bindings/vintage_coreFFI.h" "$OUT/headers/"
cp "$OUT/bindings/vintage_coreFFI.modulemap" "$OUT/headers/module.modulemap"
rm -rf "$OUT/bindings"

# Simulador: biblioteca universal (Apple Silicon + Intel)
mkdir -p "$OUT/sim"
lipo -create \
  target/aarch64-apple-ios-sim/release/libvintage_core.a \
  target/x86_64-apple-ios/release/libvintage_core.a \
  -output "$OUT/sim/libvintage_core.a"

xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libvintage_core.a -headers "$OUT/headers" \
  -library "$OUT/sim/libvintage_core.a" -headers "$OUT/headers" \
  -output "$OUT/VintageCore.xcframework"
rm -rf "$OUT/sim"
echo "Listo: $OUT/VintageCore.xcframework y $OUT/vintage_core.swift"
