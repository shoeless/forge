#!/bin/bash
# Automated Card Replacement Tournament
# For each candidate card, swap it in for Absolute Grace and run 400 games
# deck1 = modified deck (with replacement), deck2 = original v1

set -e

ORIGINAL_DECK="/private/tmp/ipad-decks/sentinel sarah lyons.dck"
RESULTS_DIR="/private/tmp/ipad-decks/results"
WORK_DIR="/Users/shoeless/Developer/forge_ios/forge/forge-gui-desktop"
DEPS=$(cat /private/tmp/classpath.txt)
DESKTOP_JAR="/Users/shoeless/Developer/forge_ios/forge/forge-gui-desktop/target/forge-gui-desktop-2.0.10-SNAPSHOT.jar"
CP="$DESKTOP_JAR:$DEPS"

NUM_GAMES=400
TIMEOUT_SEC=120
THREADS=$(sysctl -n hw.ncpu)

mkdir -p "$RESULTS_DIR"

# List of replacement cards (one per line)
CARDS=(
  "Academy Manufactor"
  "All That Glitters"
  "Basilisk Collar"
  "Bottle-Cap Blast"
  "Brotherhood Scribe"
  "Craig Boone, Novac Guard"
  "Crimson Caravaneer"
  "Diamond Pick-Axe"
  "Duchess, Wayward Tavernkeep"
  "Endurance Bobblehead"
  "Fireshrieker"
  "Grim Reaper's Sprint"
  "Idol of Oblivion"
  "Intelligence Bobblehead"
  "Junk Jet"
  "Machinist's Arsenal"
  "Mask of Memory"
  "Minas Tirith"
  "Moira Brown, Guide Author"
  "Pre-War Formalwear"
  "Radiant Summit"
  "Reckless Fireweaver"
  "Rogue's Passage"
  "Sentry Bot"
  "Sophina, Spearsage Deserter"
  "Sticky Fingers"
  "Stridehangar Automaton"
  "Swiftfoot Boots"
  "Threefold Thunderhulk"
  "Tocasia's Welcome"
  "Valorous Stance"
  "Voyager Quickwelder"
  "Wayfarer's Bauble"
)

TOTAL=${#CARDS[@]}
echo "=============================================="
echo "Card Replacement Tournament"
echo "Swapping out: Absolute Grace"
echo "Testing $TOTAL replacement cards x $NUM_GAMES games each"
echo "Total games: $((TOTAL * NUM_GAMES))"
echo "Threads: $THREADS"
echo "=============================================="
echo ""

COMPLETED=0
SUMMARY_FILE="$RESULTS_DIR/summary.txt"
cat > "$SUMMARY_FILE" << 'HEADER'
=== Card Replacement Tournament Summary ===
Swapping out: Absolute Grace

HEADER
printf "%-36s %6s %6s %6s %8s  %-24s  %9s %6s %6s %6s %6s %6s %7s %7s\n" \
  "Replacement Card" "Wins" "Losses" "Draws" "WinRate" "95% CI" \
  "AvgScore" "Dmg" "Mana" "Block" "Buff" "Rmvl" "Drawn" "Played" >> "$SUMMARY_FILE"
printf "%-36s %6s %6s %6s %8s  %-24s  %9s %6s %6s %6s %6s %6s %7s %7s\n" \
  "------------------------------------" "------" "------" "------" "--------" "------------------------" \
  "---------" "------" "------" "------" "------" "------" "-------" "-------" >> "$SUMMARY_FILE"

for CARD in "${CARDS[@]}"; do
  COMPLETED=$((COMPLETED + 1))

  # Create safe filename
  SAFE_NAME=$(echo "$CARD" | tr ' ,:' '_' | tr -d "'")
  DECK_FILE="/private/tmp/ipad-decks/deck_${SAFE_NAME}.dck"
  OUTPUT_FILE="$RESULTS_DIR/${SAFE_NAME}.txt"

  echo "[$COMPLETED/$TOTAL] Testing: $CARD"

  # Create modified deck - replace Absolute Grace line
  sed "s/^1 Absolute Grace|USG|\[1\]/1 $CARD/" "$ORIGINAL_DECK" > "$DECK_FILE"

  # Update deck name in metadata to distinguish in results
  sed -i '' "s/^Name=Sentinel Sarah Lyons$/Name=Sarah Lyons +${CARD}/" "$DECK_FILE"

  # Run the battle
  cd "$WORK_DIR"
  java -Xmx4g -cp "$CP" forge.DeckBattler \
    "$DECK_FILE" \
    "$ORIGINAL_DECK" \
    $NUM_GAMES $TIMEOUT_SEC $THREADS \
    > "$OUTPUT_FILE" 2>&1

  # Extract win/loss results
  WIN_LINE=$(grep "Sarah Lyons +${CARD} wins:" "$OUTPUT_FILE" 2>/dev/null | tail -1 || echo "")
  LOSS_LINE=$(grep "Sentinel Sarah Lyons wins:" "$OUTPUT_FILE" 2>/dev/null | tail -1 || echo "")
  WINRATE_LINE=$(grep "Win rate:" "$OUTPUT_FILE" 2>/dev/null | tail -1 || echo "")

  # Parse win/loss numbers
  WINS=$(echo "$WIN_LINE" | grep -o '[0-9]*' | head -1 || echo "0")
  LOSSES=$(echo "$LOSS_LINE" | grep -o '[0-9]*' | head -1 || echo "0")
  DRAWS=$((NUM_GAMES - WINS - LOSSES))

  if [ -n "$WINRATE_LINE" ]; then
    WINRATE=$(echo "$WINRATE_LINE" | grep -o '[0-9.]*%' | head -1 || echo "?")
    CI=$(echo "$WINRATE_LINE" | grep -o '\[.*\]' || echo "")
  else
    WINRATE="?"
    CI=""
  fi

  # Extract the replacement card's performance stats from the Card Performance Report
  # The card name may be truncated to 32 chars in the report, so search with a prefix
  SEARCH_NAME="$CARD"
  if [ ${#SEARCH_NAME} -gt 32 ]; then
    SEARCH_NAME="${SEARCH_NAME:0:29}"
  fi
  # Escape special regex chars in card name for grep
  ESCAPED_NAME=$(echo "$SEARCH_NAME" | sed 's/[.[\*^$()+?{|]/\\&/g')
  CARD_STATS_LINE=$(grep "$ESCAPED_NAME" "$OUTPUT_FILE" 2>/dev/null | grep -E '^\s*[0-9]+\.' | head -1 || echo "")

  # Parse card performance stats (format: rank. name  avgscore  dmg  mana  block  buff  rmvl  drawn  played)
  if [ -n "$CARD_STATS_LINE" ]; then
    # Extract numeric fields from the stats line
    AVG_SCORE=$(echo "$CARD_STATS_LINE" | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+\.[0-9]$/ && !found) {print $i; found=1}}')
    # More robust: grab everything after the card name portion using the known column positions
    # The format is fixed-width, so extract from the right end
    STATS_NUMS=$(echo "$CARD_STATS_LINE" | grep -oE '[0-9]+\.[0-9]' || echo "")
    AVG_SCORE=$(echo "$STATS_NUMS" | sed -n '1p')
    DMG=$(echo "$STATS_NUMS" | sed -n '2p')
    MANA=$(echo "$STATS_NUMS" | sed -n '3p')
    BLOCK=$(echo "$STATS_NUMS" | sed -n '4p')
    BUFF=$(echo "$STATS_NUMS" | sed -n '5p')
    RMVL=$(echo "$STATS_NUMS" | sed -n '6p')
    # Extract drawn and played columns (format: NNN/NN  NNN/NN)
    DRAWN=$(echo "$CARD_STATS_LINE" | grep -oE '[0-9]+/[0-9]+' | head -1 || echo "?/?")
    PLAYED=$(echo "$CARD_STATS_LINE" | grep -oE '[0-9]+/[0-9]+' | tail -1 || echo "?/?")
  else
    AVG_SCORE="?" ; DMG="?" ; MANA="?" ; BLOCK="?" ; BUFF="?" ; RMVL="?"
    DRAWN="?/?" ; PLAYED="?/?"
  fi

  echo "  Result: ${WINS}W-${LOSSES}L-${DRAWS}D (${WINRATE}) | Score:${AVG_SCORE} Dmg:${DMG} Mana:${MANA} Blk:${BLOCK} Buff:${BUFF} Rmvl:${RMVL} Drawn:${DRAWN} Played:${PLAYED}"

  printf "%-36s %6s %6s %6s %8s  %-24s  %9s %6s %6s %6s %6s %6s %7s %7s\n" \
    "$CARD" "$WINS" "$LOSSES" "$DRAWS" "$WINRATE" "$CI" \
    "${AVG_SCORE:-?}" "${DMG:-?}" "${MANA:-?}" "${BLOCK:-?}" "${BUFF:-?}" "${RMVL:-?}" "${DRAWN:-?/?}" "${PLAYED:-?/?}" \
    >> "$SUMMARY_FILE"

  # Clean up temp deck
  rm -f "$DECK_FILE"
done

echo ""
echo "=============================================="
echo "ALL DONE! Results in: $RESULTS_DIR/"
echo "=============================================="
echo ""

# Append the full per-card performance reports directory listing
echo "" >> "$SUMMARY_FILE"
echo "Full per-card performance reports saved in: $RESULTS_DIR/" >> "$SUMMARY_FILE"
echo "Each file contains the complete Card Performance Report for all 99 deck cards." >> "$SUMMARY_FILE"

# Print final summary
cat "$SUMMARY_FILE"
