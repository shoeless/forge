#!/bin/bash
# battle.sh - Run AI vs AI deck battles using DeckBattler
#
# Usage:
#   ./battle.sh <deck1.dck> <deck2.dck> [numGames]
#   ./battle.sh --ipad <deckname1> <deckname2> [numGames]
#
# Examples:
#   ./battle.sh /tmp/princess_kaalia.dck /tmp/kaalia.dck 100
#   ./battle.sh --ipad "princess kaalia" "kaalia" 200

set -e -o pipefail

FORGE_ROOT="$(cd "$(dirname "$0")" && pwd)"
VERSION=$(grep -A1 '<versionCode>' "$FORGE_ROOT/pom.xml" | grep -o '[0-9.]*')
DESKTOP_JAR="$FORGE_ROOT/forge-gui-desktop/target/forge-gui-desktop-${VERSION}-SNAPSHOT.jar"
CP_CACHE="/tmp/forge-battle-classpath.txt"
LOG_DIR="$FORGE_ROOT/logs"

IPAD_ID="00008101-001A41820113A01E"
IPAD_DOMAIN_TYPE="appDataContainer"
IPAD_DOMAIN_ID="com.mathforthemasses.forge.ios"

TIMEOUT_SEC=120
THREADS=$(sysctl -n hw.ncpu 2>/dev/null || echo 4)

# --- Parse arguments ---
FROM_IPAD=false
if [ "$1" = "--ipad" ]; then
    FROM_IPAD=true
    shift
fi

if [ $# -lt 2 ]; then
    echo "Usage: $0 [--ipad] <deck1> <deck2> [numGames]"
    echo ""
    echo "  --ipad    Pull decks from iPad by name (from Documents/decks/commander/)"
    echo "  numGames  Number of games to play (default: 100)"
    exit 1
fi

DECK1="$1"
DECK2="$2"
NUM_GAMES="${3:-100}"

# --- Pull decks from iPad if requested ---
if $FROM_IPAD; then
    echo "Pulling decks from iPad..."
    DECK1_FILE="/tmp/$(echo "$DECK1" | tr ' ' '_').dck"
    DECK2_FILE="/tmp/$(echo "$DECK2" | tr ' ' '_').dck"

    xcrun devicectl device copy from --device "$IPAD_ID" \
        --source "Documents/decks/commander/${DECK1}.dck" \
        --domain-type "$IPAD_DOMAIN_TYPE" --domain-identifier "$IPAD_DOMAIN_ID" \
        --destination "$DECK1_FILE" 2>&1 | grep -v "Acquired\|Enabling"

    xcrun devicectl device copy from --device "$IPAD_ID" \
        --source "Documents/decks/commander/${DECK2}.dck" \
        --domain-type "$IPAD_DOMAIN_TYPE" --domain-identifier "$IPAD_DOMAIN_ID" \
        --destination "$DECK2_FILE" 2>&1 | grep -v "Acquired\|Enabling"

    DECK1="$DECK1_FILE"
    DECK2="$DECK2_FILE"
    echo "  Deck 1: $DECK1"
    echo "  Deck 2: $DECK2"
fi

# --- Verify deck files exist ---
if [ ! -f "$DECK1" ]; then
    echo "Error: Deck file not found: $DECK1"
    exit 1
fi
if [ ! -f "$DECK2" ]; then
    echo "Error: Deck file not found: $DECK2"
    exit 1
fi

# --- Verify desktop JAR exists ---
if [ ! -f "$DESKTOP_JAR" ]; then
    echo "Error: Desktop JAR not found: $DESKTOP_JAR"
    echo "Run: mvn clean install -DskipTests"
    exit 1
fi

# --- Ensure cardsfolder.zip reflects any card-script (.txt) edits ---
# The runtime loads cards from cardsfolder.zip (CardStorageReader), NOT the loose .txt files,
# and the binary card cache keys off the zip's mtime. Rebuild the zip if any .txt is newer so
# local script edits actually take effect (rebuilding also auto-invalidates the card cache).
CARDS_DIR="$FORGE_ROOT/forge-gui/res/cardsfolder"
CARDS_ZIP="$CARDS_DIR/cardsfolder.zip"
if [ ! -f "$CARDS_ZIP" ] || [ -n "$(find "$CARDS_DIR" -name '*.txt' -newer "$CARDS_ZIP" -print -quit 2>/dev/null)" ]; then
    echo "Card scripts changed since last zip — rebuilding cardsfolder.zip..."
    ( cd "$CARDS_DIR" && bash mkzip.sh >/dev/null 2>&1 )
fi

# --- Generate classpath (cached, regenerated if JAR is newer) ---
if [ ! -f "$CP_CACHE" ] || [ "$DESKTOP_JAR" -nt "$CP_CACHE" ]; then
    echo "Generating classpath..."
    DEPS=$(cd "$FORGE_ROOT" && mvn -pl forge-gui-desktop dependency:build-classpath \
        -DincludeScope=runtime -q -Dmdep.outputFile=/dev/stdout 2>/dev/null)
    echo "$DESKTOP_JAR:$DEPS" > "$CP_CACHE"
fi
CP=$(cat "$CP_CACHE")

# --- Run the battle ---
mkdir -p "$LOG_DIR"
LOGFILE="$LOG_DIR/battle-$(date +%Y%m%d-%H%M%S).log"

echo ""
echo "=== Deck Battle ==="
echo "  Games: $NUM_GAMES"
echo "  Threads: $THREADS"
echo "  Timeout: ${TIMEOUT_SEC}s per game"
echo "  Log: $LOGFILE"
echo ""

cd "$FORGE_ROOT/forge-gui-desktop"
java -Xmx4g -cp "$CP" forge.DeckBattler \
    "$DECK1" "$DECK2" \
    "$NUM_GAMES" "$TIMEOUT_SEC" "$THREADS" \
    2>&1 | tee "$LOGFILE"

echo ""
echo "Full log saved to: $LOGFILE"
