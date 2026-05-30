#!/bin/bash
set -e

#
# deploy-testflight.sh - Build, sign, and upload Forge iOS to TestFlight
#
# Usage: ./deploy-testflight.sh [--skip-build] [--skip-modules]
#
# Options:
#   --skip-build    Skip IPA build, just re-sign and upload existing build
#   --skip-modules  Skip rebuilding Java modules (use if only iOS code changed)
#

FORGE_ROOT="/Users/shoeless/Developer/forge_ios/forge"
IOS_DIR="$FORGE_ROOT/forge-gui-ios"
PROPERTIES="$IOS_DIR/robovm.properties"
MAVEN_SETTINGS="$FORGE_ROOT/.mvn/local-settings.xml"
PROVISIONING_PROFILE="/Users/shoeless/Downloads/Forge_iOS_Distribution.mobileprovision"
SIGNING_IDENTITY="iPhone Distribution: Christopher Sholley (MPMKTL6SWS)"
TEAM_ID="MPMKTL6SWS"
BUNDLE_ID="com.mathforthemasses.forge.ios"
APPLE_ID="csholley@alumni.stanford.edu"

# Load app-specific password from .env
if [ -f "$FORGE_ROOT/.env" ]; then
    ALTOOL_PW=$(grep 'ALTOOL_PW' "$FORGE_ROOT/.env" | sed "s/.*= *'//;s/'.*//")
else
    echo "ERROR: .env file not found. Create $FORGE_ROOT/.env with ALTOOL_PW = 'your-app-specific-password'"
    exit 1
fi

# Log all output to a timestamped file (the build is long and can exceed
# terminal buffer limits, causing false "exit code 1" when run from tools
# with limited output buffers like Claude Code's Bash tool)
LOG_DIR="$FORGE_ROOT/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/testflight-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee "$LOG_FILE") 2>&1
echo "Logging to: $LOG_FILE"

# Temp working directory
WORK_DIR="/tmp/forge-testflight"
ASSET_DIR="/tmp/ForgeAssets.xcassets"
IPA_OUT="/tmp/Forge.ipa"

SKIP_BUILD=false
SKIP_MODULES=false

for arg in "$@"; do
    case $arg in
        --skip-build)  SKIP_BUILD=true ;;
        --skip-modules) SKIP_MODULES=true ;;
    esac
done

# -------------------------------------------------------
# Step 1: Bump build number
# -------------------------------------------------------
echo "=== Step 1: Bumping build number ==="
CURRENT_BUILD=$(grep 'app.build=' "$PROPERTIES" | cut -d= -f2)
NEW_BUILD=$((CURRENT_BUILD + 1))
sed -i '' "s/app.build=$CURRENT_BUILD/app.build=$NEW_BUILD/" "$PROPERTIES"
echo "Build number: $CURRENT_BUILD -> $NEW_BUILD"

if [ "$SKIP_BUILD" = true ]; then
    echo "=== Skipping build (--skip-build) ==="
else
    # -------------------------------------------------------
    # Step 2: Build Java modules
    # -------------------------------------------------------
    if [ "$SKIP_MODULES" = true ]; then
        echo "=== Skipping module rebuild (--skip-modules) ==="
    else
        echo "=== Step 2: Building Java modules ==="
        cd "$FORGE_ROOT"
        mvn clean install -pl forge-core,forge-game,forge-gui,forge-gui-mobile,forge-ai -DskipTests
    fi

    # -------------------------------------------------------
    # Step 2.5: Build cardsfolder.zip
    # -------------------------------------------------------
    echo "=== Step 2.5: Building cardsfolder.zip ==="
    cd "$FORGE_ROOT/forge-gui/res/cardsfolder"
    bash mkzip.sh 2>&1 | tail -1
    echo "  Done ($(du -h cardsfolder.zip | cut -f1))"
    cd "$FORGE_ROOT"

    # -------------------------------------------------------
    # Step 3: Fix Maven ${revision} placeholders
    # -------------------------------------------------------
    echo "=== Step 3: Fixing Maven revision placeholders ==="
    cd "$FORGE_ROOT"
    VERSION=$(grep -A1 '<versionCode>' pom.xml | grep -o '[0-9.]*')-SNAPSHOT
    find ~/.m2/repository/forge -name "*.pom" -exec sed -i '' "s/\${revision}/$VERSION/g" {} \;

    # -------------------------------------------------------
    # Step 4: Build IPA
    # -------------------------------------------------------
    echo "=== Step 4: Building IPA ==="
    cd "$IOS_DIR"
    mvn clean robovm:create-ipa -DskipTests --settings "$MAVEN_SETTINGS"
fi

# -------------------------------------------------------
# Step 5: Unzip and prepare app bundle
# -------------------------------------------------------
echo "=== Step 5: Preparing app bundle ==="
IPA_SRC="$IOS_DIR/target/robovm/forge.ios.Main.ipa"
if [ ! -f "$IPA_SRC" ]; then
    echo "ERROR: IPA not found at $IPA_SRC"
    exit 1
fi

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"
unzip -q "$IPA_SRC"
APP="$WORK_DIR/Payload/forge.ios.Main.app"

# -------------------------------------------------------
# Step 6: Add provisioning profile
# -------------------------------------------------------
echo "=== Step 6: Adding provisioning profile ==="
if [ ! -f "$PROVISIONING_PROFILE" ]; then
    echo "ERROR: Provisioning profile not found at $PROVISIONING_PROFILE"
    exit 1
fi
cp "$PROVISIONING_PROFILE" "$APP/embedded.mobileprovision"

# -------------------------------------------------------
# Step 7: Create and compile asset catalog (icons)
# -------------------------------------------------------
echo "=== Step 7: Building asset catalog ==="

# Create asset catalog structure if it doesn't exist
if [ ! -d "$ASSET_DIR/AppIcon.appiconset" ]; then
    mkdir -p "$ASSET_DIR/AppIcon.appiconset"

    # Generate all required sizes from 1024px source
    ICON_SRC="$IOS_DIR/Icon-1024.png"

    # Remove alpha by round-tripping through JPEG
    sips -s format jpeg -s formatOptions 100 "$ICON_SRC" --out /tmp/icon_temp.jpg >/dev/null 2>&1
    sips -s format png /tmp/icon_temp.jpg --out "$ASSET_DIR/AppIcon.appiconset/Icon-1024.png" >/dev/null 2>&1
    rm -f /tmp/icon_temp.jpg

    # Generate missing sizes
    sips -z 40 40 "$ICON_SRC" --out "$ASSET_DIR/AppIcon.appiconset/Icon-20@2x.png" >/dev/null 2>&1
    sips -z 60 60 "$ICON_SRC" --out "$ASSET_DIR/AppIcon.appiconset/Icon-20@3x.png" >/dev/null 2>&1
    sips -z 58 58 "$ICON_SRC" --out "$ASSET_DIR/AppIcon.appiconset/Icon-29@2x.png" >/dev/null 2>&1
    sips -z 87 87 "$ICON_SRC" --out "$ASSET_DIR/AppIcon.appiconset/Icon-29@3x.png" >/dev/null 2>&1

    # Copy existing sizes
    cp "$IOS_DIR/Icon-40@2x.png" "$ASSET_DIR/AppIcon.appiconset/"
    cp "$IOS_DIR/Icon-40@3x.png" "$ASSET_DIR/AppIcon.appiconset/"
    cp "$IOS_DIR/Icon-60@2x.png" "$ASSET_DIR/AppIcon.appiconset/"
    cp "$IOS_DIR/Icon-60@3x.png" "$ASSET_DIR/AppIcon.appiconset/"
    cp "$IOS_DIR/Icon-76.png" "$ASSET_DIR/AppIcon.appiconset/"
    cp "$IOS_DIR/Icon-76@2x.png" "$ASSET_DIR/AppIcon.appiconset/"
    cp "$IOS_DIR/Icon-83.5@2x.png" "$ASSET_DIR/AppIcon.appiconset/"

    cat > "$ASSET_DIR/AppIcon.appiconset/Contents.json" << 'ICONJSON'
{
  "images" : [
    { "size" : "20x20", "idiom" : "iphone", "filename" : "Icon-20@2x.png", "scale" : "2x" },
    { "size" : "20x20", "idiom" : "iphone", "filename" : "Icon-20@3x.png", "scale" : "3x" },
    { "size" : "29x29", "idiom" : "iphone", "filename" : "Icon-29@2x.png", "scale" : "2x" },
    { "size" : "29x29", "idiom" : "iphone", "filename" : "Icon-29@3x.png", "scale" : "3x" },
    { "size" : "40x40", "idiom" : "iphone", "filename" : "Icon-40@2x.png", "scale" : "2x" },
    { "size" : "40x40", "idiom" : "iphone", "filename" : "Icon-40@3x.png", "scale" : "3x" },
    { "size" : "60x60", "idiom" : "iphone", "filename" : "Icon-60@2x.png", "scale" : "2x" },
    { "size" : "60x60", "idiom" : "iphone", "filename" : "Icon-60@3x.png", "scale" : "3x" },
    { "size" : "20x20", "idiom" : "ipad", "filename" : "Icon-20@2x.png", "scale" : "2x" },
    { "size" : "29x29", "idiom" : "ipad", "filename" : "Icon-29@2x.png", "scale" : "2x" },
    { "size" : "40x40", "idiom" : "ipad", "filename" : "Icon-40@2x.png", "scale" : "2x" },
    { "size" : "76x76", "idiom" : "ipad", "filename" : "Icon-76.png", "scale" : "1x" },
    { "size" : "76x76", "idiom" : "ipad", "filename" : "Icon-76@2x.png", "scale" : "2x" },
    { "size" : "83.5x83.5", "idiom" : "ipad", "filename" : "Icon-83.5@2x.png", "scale" : "2x" },
    { "size" : "1024x1024", "idiom" : "ios-marketing", "filename" : "Icon-1024.png", "scale" : "1x" }
  ],
  "info" : { "version" : 1, "author" : "xcode" }
}
ICONJSON

    cat > "$ASSET_DIR/Contents.json" << 'ROOTJSON'
{ "info" : { "version" : 1, "author" : "xcode" } }
ROOTJSON

    echo "Asset catalog created"
else
    echo "Asset catalog already exists, reusing"
fi

# Add loose iPad 76x76 icon
cp "$IOS_DIR/Icon-76.png" "$APP/AppIcon76x76~ipad.png"

# Compile asset catalog
xcrun actool "$ASSET_DIR" \
    --compile "$APP" \
    --platform iphoneos \
    --minimum-deployment-target 12.0 \
    --app-icon AppIcon \
    --output-partial-info-plist /tmp/assetcatalog_info.plist >/dev/null 2>&1

# Verify Assets.car was created
if [ ! -f "$APP/Assets.car" ]; then
    echo "ERROR: Asset catalog compilation failed. Try running: sudo xcodebuild -runFirstLaunch"
    exit 1
fi

# -------------------------------------------------------
# Step 8: Update Info.plist with icon references
# -------------------------------------------------------
echo "=== Step 8: Updating Info.plist ==="
PB=/usr/libexec/PlistBuddy

$PB -c "Add :CFBundleIconName string AppIcon" "$APP/Info.plist" 2>/dev/null || \
$PB -c "Set :CFBundleIconName AppIcon" "$APP/Info.plist"

$PB -c "Set :CFBundleIcons:CFBundlePrimaryIcon:CFBundleIconFiles:0 AppIcon60x60" "$APP/Info.plist" 2>/dev/null
$PB -c "Set :CFBundleIcons:CFBundlePrimaryIcon:CFBundleIconFiles:1 AppIcon60x60" "$APP/Info.plist" 2>/dev/null
$PB -c "Add :CFBundleIcons:CFBundlePrimaryIcon:CFBundleIconName string AppIcon" "$APP/Info.plist" 2>/dev/null

$PB -c "Add :CFBundleIcons~ipad dict" "$APP/Info.plist" 2>/dev/null
$PB -c "Add :CFBundleIcons~ipad:CFBundlePrimaryIcon dict" "$APP/Info.plist" 2>/dev/null
$PB -c "Add :CFBundleIcons~ipad:CFBundlePrimaryIcon:CFBundleIconFiles array" "$APP/Info.plist" 2>/dev/null
$PB -c "Add :CFBundleIcons~ipad:CFBundlePrimaryIcon:CFBundleIconFiles:0 string AppIcon60x60" "$APP/Info.plist" 2>/dev/null
$PB -c "Add :CFBundleIcons~ipad:CFBundlePrimaryIcon:CFBundleIconFiles:1 string AppIcon76x76" "$APP/Info.plist" 2>/dev/null
$PB -c "Add :CFBundleIcons~ipad:CFBundlePrimaryIcon:CFBundleIconName string AppIcon" "$APP/Info.plist" 2>/dev/null

# -------------------------------------------------------
# Step 9: Create entitlements and sign
# -------------------------------------------------------
echo "=== Step 9: Code signing ==="

cat > /tmp/dist-entitlements.plist << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>application-identifier</key>
    <string>${TEAM_ID}.${BUNDLE_ID}</string>
    <key>com.apple.developer.team-identifier</key>
    <string>${TEAM_ID}</string>
    <key>keychain-access-groups</key>
    <array>
        <string>${TEAM_ID}.*</string>
    </array>
</dict>
</plist>
EOF

for fw in "$APP/Frameworks/"*.framework; do
    codesign -f -s "$SIGNING_IDENTITY" "$fw"
done

codesign -f -s "$SIGNING_IDENTITY" \
    --entitlements /tmp/dist-entitlements.plist \
    --generate-entitlement-der \
    "$APP"

# Verify
codesign -vvv "$APP" 2>&1 | grep -q "valid on disk" || { echo "ERROR: Code signing verification failed"; exit 1; }
echo "Signature valid"

# -------------------------------------------------------
# Step 10: Package IPA
# -------------------------------------------------------
echo "=== Step 10: Packaging IPA ==="
cd "$WORK_DIR"
rm -f "$IPA_OUT"
zip -r -q "$IPA_OUT" Payload/
echo "IPA created: $IPA_OUT ($(du -h "$IPA_OUT" | cut -f1))"

# -------------------------------------------------------
# Step 11: Upload to App Store Connect
# -------------------------------------------------------
echo "=== Step 11: Uploading to App Store Connect ==="
OUTPUT=$(xcrun altool --upload-app -f "$IPA_OUT" -t ios -u "$APPLE_ID" -p "$ALTOOL_PW" 2>&1)

if echo "$OUTPUT" | grep -q "UPLOAD SUCCEEDED"; then
    echo "$OUTPUT" | grep -E "UPLOAD SUCCEEDED|Delivery UUID|Transferred"
    echo ""
    echo "=== SUCCESS ==="
    echo "Build $NEW_BUILD uploaded to TestFlight!"
    echo "Check App Store Connect in ~5-15 minutes for the new build."
else
    echo "ERROR: Upload failed"
    echo "$OUTPUT"
    exit 1
fi

# Cleanup
rm -rf "$WORK_DIR"
