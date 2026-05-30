#!/bin/bash
# verify-ai-playable.sh - Check whether Forge's AI can pilot every card in a deck.
#
# Forge card scripts carry AI-support hints. The strongest signals that the AI
# cannot use a card well are:
#   AI:RemoveDeck:All     -> AI cannot play this card at all (excluded from AI decks)
#   AI:RemoveDeck:Random  -> AI handles it poorly (excluded from random AI decks)
# We also flag cards whose script file cannot be found (name mismatch / not in DB).
#
# Usage: ./verify-ai-playable.sh <deck.dck>

set -o pipefail
CARDS_DIR="/Users/shoeless/Developer/forge_ios/forge/forge-gui/res/cardsfolder"
DECK="$1"

if [ ! -f "$DECK" ]; then
    echo "Error: deck file not found: $DECK"
    exit 1
fi

echo "=== AI Playability Check: $(basename "$DECK") ==="

# Extract card names from [Main] and [Commander] sections (skip [Sideboard]).
names=$(awk '
    /^\[Main\]/      {sec="main"; next}
    /^\[Commander\]/ {sec="cmd";  next}
    /^\[Sideboard\]/ {sec="side"; next}
    /^\[/            {sec="";     next}
    (sec=="main" || sec=="cmd") && /\|/ {
        line=$0
        sub(/^[0-9]+ /, "", line)     # strip leading count
        sub(/\|.*/, "", line)         # strip |SET|[num]
        print line
    }
' "$DECK")

removeall=0; removerandom=0; missing=0; ok=0
while IFS= read -r name; do
    [ -z "$name" ] && continue
    # Forge filename: lowercase, spaces->underscores, strip ' , and other punctuation
    fn=$(echo "$name" | tr '[:upper:]' '[:lower:]' | tr ' ' '_' | tr -d "',.!?:" )
    file=$(ls "$CARDS_DIR"/*/"${fn}.txt" 2>/dev/null | head -1)
    # Fallback 1: double-faced cards are stored as <front>_<back>.txt
    if [ -z "$file" ]; then
        file=$(ls "$CARDS_DIR"/*/"${fn}"_*.txt 2>/dev/null | head -1)
    fi
    # Fallback 2: resolve by exact front-face Name: line (handles any name/filename mismatch)
    if [ -z "$file" ]; then
        file=$(grep -rl "^Name:${name}$" "$CARDS_DIR" 2>/dev/null | head -1)
    fi
    if [ -z "$file" ]; then
        echo "  [MISSING SCRIPT] $name  (looked for ${fn}.txt)"
        missing=$((missing+1))
        continue
    fi
    flag=$(grep -h "^AI:RemoveDeck" "$file" 2>/dev/null | head -1)
    if echo "$flag" | grep -q "RemoveDeck:All"; then
        echo "  [AI CANNOT PLAY] $name  ->  $flag"
        removeall=$((removeall+1))
    elif echo "$flag" | grep -q "RemoveDeck:Random"; then
        echo "  [AI WEAK]        $name  ->  $flag"
        removerandom=$((removerandom+1))
    else
        ok=$((ok+1))
    fi
done <<< "$names"

echo "---"
echo "  OK (AI plays normally): $ok"
echo "  AI WEAK (RemoveDeck:Random): $removerandom"
echo "  AI CANNOT PLAY (RemoveDeck:All): $removeall"
echo "  MISSING SCRIPT (name mismatch / not in DB): $missing"
