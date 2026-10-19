#!/usr/bin/env bash
# Build "TikTok SF Patcher.app" into macos/dist/.
#
#   ./macos/make_app.sh
#
# The app carries the mod's sources and tools; it needs only python3 and a JDK
# on the Mac, and adb (Android platform-tools) to put the result on a phone.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
APP="$ROOT/macos/dist/TikTok SF Patcher.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/mod"

swiftc -O -parse-as-library -target arm64-apple-macos13.0 \
    "$ROOT/macos/Patcher.swift" -o "$APP/Contents/MacOS/TikTokSFPatcher"

# what the build reads, and nothing else
rsync -a \
    --exclude '__pycache__' --exclude '*.pyc' \
    margyt inject icons icon_out assets VERSION version.json badges.json tools "$APP/Contents/Resources/mod/"
mkdir -p "$APP/Contents/Resources/mod/docs" "$APP/Contents/Resources/mod/server"
rsync -a docs/ "$APP/Contents/Resources/mod/docs/"
rm -f "$APP/Contents/Resources/mod/assets/tiktok_logo.png"

# the icon, from the same picture the app icon is
ICONS="$(mktemp -d)/app.iconset"
mkdir -p "$ICONS"
for size in 16 32 64 128 256 512; do
    sips -z $size $size icon_out/margyt-1024.png --out "$ICONS/icon_${size}x${size}.png" >/dev/null
    sips -z $((size*2)) $((size*2)) icon_out/margyt-1024.png --out "$ICONS/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONS" -o "$APP/Contents/Resources/AppIcon.icns"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>TikTok SF Patcher</string>
<key>CFBundleDisplayName</key><string>TikTok SF Patcher</string>
<key>CFBundleIdentifier</key><string>sf.tiktok.patcher</string>
<key>CFBundleExecutable</key><string>TikTokSFPatcher</string>
<key>CFBundleIconFile</key><string>AppIcon</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSMinimumSystemVersion</key><string>13.0</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST

codesign --force --deep --sign - "$APP"
echo "built: $APP"
