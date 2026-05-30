#!/bin/bash
# deploy-ipads.sh - Build, sign, deploy to both iPads, and start log streams
# Usage:
#   ./deploy-ipads.sh              # Full build + deploy + logs
#   ./deploy-ipads.sh --skip-modules  # Skip Java module rebuild
#   ./deploy-ipads.sh --skip-build    # Skip all building, just sign + deploy + logs
#   ./deploy-ipads.sh --logs-only     # Just start log streams (no build/deploy)

set -e -o pipefail

FORGE_ROOT="/Users/shoeless/Developer/forge_ios/forge"
APP_PATH="$FORGE_ROOT/forge-gui-ios/target/robovm.tmp/forge.ios.Main.app"
PROFILE="/Users/shoeless/Library/Developer/Xcode/DerivedData/forge.ios-bzixpslydnruwkgrdkbaxbyuobvp/Build/Products/Debug-iphoneos/forge.ios.app/embedded.mobileprovision"
SIGN_ID="Apple Development: Christopher Sholley (2BLY48TMNK)"
TEAM_ID="MPMKTL6SWS"
ENTITLEMENTS="/tmp/entitlements.plist"

IPAD1_ID="00008101-001A41820113A01E"  # Chris Sholley's iPad (iOS 26.2)
IPAD2_ID="21873d3f6ec58ec908c4fcb1dc0a923eaf86165c"  # Chris's iPad (iOS 16)

LOG_DIR="/tmp"
IPAD1_LOG="$LOG_DIR/ipad1_logs.txt"
IPAD2_LOG="$LOG_DIR/ipad2_logs.txt"

SKIP_MODULES=false
SKIP_BUILD=false
LOGS_ONLY=false

for arg in "$@"; do
    case $arg in
        --skip-modules) SKIP_MODULES=true ;;
        --skip-build) SKIP_BUILD=true ;;
        --logs-only) LOGS_ONLY=true ;;
    esac
done

elapsed() {
    local start=$1
    local end=$(date +%s)
    echo "$((end - start))s"
}

# --- Stop existing log streams ---
stop_logs() {
    pkill -f "idevicesyslog.*$IPAD1_ID" 2>/dev/null || true
    pkill -f "idevicesyslog.*$IPAD2_ID" 2>/dev/null || true
}

# --- Start log streams ---
start_logs() {
    echo ""
    echo "=== Starting log streams ==="
    stop_logs
    sleep 1

    > "$IPAD1_LOG"
    > "$IPAD2_LOG"

    idevicesyslog -u "$IPAD1_ID" -p forge.ios.Main >> "$IPAD1_LOG" 2>&1 &
    echo "  iPad 1 log: $IPAD1_LOG (PID: $!)"

    idevicesyslog -u "$IPAD2_ID" -p forge.ios.Main >> "$IPAD2_LOG" 2>&1 &
    echo "  iPad 2 log: $IPAD2_LOG (PID: $!)"

    echo ""
    echo "  Monitor logs:"
    echo "    tail -f $IPAD1_LOG | grep '\\[ERR\\]'"
    echo "    tail -f $IPAD2_LOG | grep '\\[ERR\\]'"
    echo "    grep 'NET SEND\\|NET ENCODER\\|NET GAMEVIEW' $IPAD1_LOG"
}

if $LOGS_ONLY; then
    start_logs
    exit 0
fi

if ! $SKIP_BUILD; then
    cd "$FORGE_ROOT"

    # --- Bump build number ---
    PROPS="$FORGE_ROOT/forge-gui-ios/robovm.properties"
    OLD_BUILD=$(grep 'app.build=' "$PROPS" | cut -d= -f2)
    NEW_BUILD=$((OLD_BUILD + 1))
    sed -i '' "s/app.build=$OLD_BUILD/app.build=$NEW_BUILD/" "$PROPS"
    echo "=== Build number: $OLD_BUILD -> $NEW_BUILD ==="

    # --- Build Java modules ---
    if ! $SKIP_MODULES; then
        echo "=== Building Java modules ==="
        T=$(date +%s)
        mvn clean install -pl forge-core,forge-game,forge-gui,forge-gui-mobile,forge-ai -DskipTests
        echo "  Modules built in $(elapsed $T)"
    fi

    # --- Build cardsfolder.zip ---
    echo "=== Building cardsfolder.zip ==="
    T=$(date +%s)
    cd "$FORGE_ROOT/forge-gui/res/cardsfolder"
    bash mkzip.sh 2>&1 | tail -1
    echo "  cardsfolder.zip built in $(elapsed $T) ($(du -h cardsfolder.zip | cut -f1))"
    cd "$FORGE_ROOT"

    # --- Fix ${revision} placeholders ---
    echo "=== Fixing Maven \${revision} placeholders ==="
    VERSION=$(grep -A1 '<versionCode>' pom.xml | grep -o '[0-9.]*')-SNAPSHOT
    find ~/.m2/repository/forge -name "*.pom" -exec sed -i '' "s/\\\${revision}/$VERSION/g" {} \;
    echo "  Fixed with version: $VERSION"

    # --- Build iOS app ---
    echo "=== Building iOS app ==="
    T=$(date +%s)
    cd "$FORGE_ROOT/forge-gui-ios"
    mvn clean package robovm:ios-device -DskipTests \
        --settings "$FORGE_ROOT/.mvn/local-settings.xml" 2>&1 | \
        grep -E "BUILD|ERROR|WARNING|Waiting for device" || true
    echo "  iOS app built in $(elapsed $T)"
fi

# --- Sign ---
echo "=== Signing app ==="
cp "$PROFILE" "$APP_PATH/embedded.mobileprovision"

# Create entitlements if missing
cat > "$ENTITLEMENTS" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>application-identifier</key>
    <string>${TEAM_ID}.com.mathforthemasses.forge.ios</string>
    <key>com.apple.developer.team-identifier</key>
    <string>${TEAM_ID}</string>
    <key>get-task-allow</key>
    <true/>
    <key>keychain-access-groups</key>
    <array>
        <string>${TEAM_ID}.*</string>
    </array>
</dict>
</plist>
EOF

cd "$APP_PATH"
for framework in Frameworks/*.framework; do
    codesign -f -s "$SIGN_ID" "$framework" 2>/dev/null
done
codesign -f -s "$SIGN_ID" --entitlements "$ENTITLEMENTS" --generate-entitlement-der "$APP_PATH"
echo "  Signed successfully"

# --- Deploy to both iPads ---
echo "=== Deploying to iPads ==="

echo "  iPad 1 (Chris Sholley's iPad)..."
T=$(date +%s)
xcrun devicectl device install app --device "$IPAD1_ID" "$APP_PATH" 2>&1 | grep -E "installed|error|Error" || true
echo "    Done in $(elapsed $T)"

echo "  iPad 2 (Chris's iPad) - deploying in background..."
ios-deploy --id "$IPAD2_ID" --bundle "$APP_PATH" > /tmp/ios-deploy.log 2>&1 &
IPAD2_DEPLOY_PID=$!
echo "    PID: $IPAD2_DEPLOY_PID (check: tail -1 /tmp/ios-deploy.log)"
echo "    This takes 3-5 minutes. Continuing with log setup..."

# --- Start log streams ---
start_logs

echo ""
echo "=== All done! ==="
echo "Launch the app on both iPads and check logs with:"
echo "  grep 'NET SEND\|NET ENCODER\|NET GAMEVIEW' $IPAD1_LOG"
