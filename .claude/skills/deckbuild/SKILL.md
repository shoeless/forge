---
name: Deck Builder
description: Build or improve a Commander deck using external research, card prices, Forge card validation, and AI simulation testing
arguments: [target]
allowed-tools: [Bash, Read, Write, Edit, Glob, Grep, WebFetch, WebSearch, Agent]
---

# Automated Commander Deck Builder

Build or improve a Commander deck through research, pricing, validation, and simulation.

## Input

`$target` is either:
- A **commander name** (e.g., "Kaalia of the Vast") → build a new deck
- A **path to a .dck file** (e.g., "/tmp/my_deck.dck") → improve an existing deck

If the user provides additional context (budget, strategy, cards to keep/cut), incorporate it.

## Workflow

### Phase 1: Understand the Commander

1. Read the commander's card script from `forge-gui/res/cardsfolder/` to understand its abilities
2. **WebSearch** for EDHREC page and budget deck guides:
   - Search: `"<commander name>" EDHREC commander deck`
   - Search: `"<commander name>" budget commander deck guide`
3. **WebFetch** the EDHREC page if found — extract:
   - Top synergy cards
   - Most-played cards by category (creatures, instants, sorceries, artifacts, enchantments, lands)
   - Win conditions and strategy archetypes
4. Summarize the commander's game plan in 2-3 sentences before proceeding

### Phase 2: Card Research & Pricing

1. For each candidate card, check its price via the Scryfall API:
   ```
   WebFetch: https://api.scryfall.com/cards/named?fuzzy=<card+name>
   ```
   Extract `prices.usd` from the JSON response. Cards over budget threshold should be flagged.

2. **Budget guidelines** (unless user specifies otherwise):
   - Per-card target: under $5
   - Total deck budget: under $100 (excluding commander and basic lands)
   - Flag any card over $10 for user review

3. Build candidate list of ~120-150 cards across categories:
   - Ramp (10-12): Sol Ring, signets, talismans, mana rocks
   - Card draw (8-10): Phyrexian Arena, Night's Whisper, Read the Bones, etc.
   - Removal (8-10): Swords to Plowshares, Path to Exile, board wipes
   - Creatures (25-30): Synergy with commander, curve considerations
   - Protection (3-5): Swiftfoot Boots, Lightning Greaves
   - Win conditions (3-5): Finishers aligned with strategy
   - Lands (35-37): Color-fixing on budget, Command Tower, utility lands

### Phase 3: Validate Cards in Forge

**CRITICAL**: Every card MUST exist in Forge's database or the deck won't load.

For each candidate card, verify it exists:
```bash
# Convert card name to filename: lowercase, spaces to underscores, remove special chars
FILENAME=$(echo "Card Name" | tr '[:upper:]' '[:lower:]' | tr ' ' '_' | tr -d "',")
ls forge-gui/res/cardsfolder/*/${FILENAME}.txt
```

If a card doesn't exist in Forge, drop it and find a replacement. Report which cards were unavailable.

To find the correct set code and collector number for the .dck file, search the edition files:
```bash
grep -r "Card Name" forge-gui/res/editions/ --include="*.txt" | head -3
```

### Phase 3.5: Verify AI Playability

**CRITICAL for simulation validity**: A card the Forge AI cannot pilot is effectively a
dead draw in every battle, which both skews win-rate results and makes the card a wasted
slot in the real deck. Always run this check on BOTH the deck being optimized and the
baseline opponent before trusting any simulation.

Forge card scripts carry AI-support hints. The two strongest signals are:
- `AI:RemoveDeck:All` — the AI **cannot play this card at all** (dead card in sims)
- `AI:RemoveDeck:Random` — the AI handles it **poorly / inconsistently**

Run the helper script (lives next to this skill):
```bash
.claude/skills/deckbuild/verify-ai-playable.sh /tmp/<deck>.dck
```

It reports, per card: `AI CANNOT PLAY` (RemoveDeck:All), `AI WEAK` (RemoveDeck:Random),
`MISSING SCRIPT` (name/DB mismatch), and `OK`. Then:

1. **`AI CANNOT PLAY` cards** — treat as high-priority cut candidates. They contribute
   nothing in simulation and usually little in AI-piloted real play. Replace with an
   AI-friendly equivalent (e.g. swap a ritual the AI ignores for a mana rock it uses).
2. **`AI WEAK` cards** — keep only if their ceiling justifies inconsistency; flag for the
   user. Prefer cutting them if a strictly-AI-friendlier option fills the same role.
3. **`MISSING SCRIPT`** — a name mismatch or card not in the DB; fix the name or drop it
   (this would otherwise fail deck loading per Phase 3).
4. Report the counts to the user and note which sim results may be distorted by dead cards.

When optimizing for a *specific* opponent, weight cuts toward AI-playable cards that
answer that opponent's plan (e.g. mass bounce/wraths vs. a go-wide tokens deck) over
narrow cards the AI can barely use.

### Phase 4: Assemble the Deck

Build a `.dck` file in Forge format:
```
[metadata]
Name=<Deck Name>
[Main]
1 Card Name|SET|[collector_number]
...
8 Mountain|SET|[num]
8 Plains|SET|[num]
...
[Commander]
1 Commander Name|SET|[collector_number]
```

**Rules for Commander decks:**
- Exactly 99 cards in [Main] + 1 in [Commander] = 100 total
- No duplicate non-basic-land cards (singleton format)
- All cards must be within the commander's color identity
- Basic land counts should sum to fill remaining slots after nonland cards

Write the deck to `/tmp/<deck_name>.dck`

### Phase 5: Simulate & Test

If an existing deck or baseline is available, run a battle simulation.

`battle.sh` auto-detects CPU cores and runs games in parallel across all threads via DeckBattler's ExecutorService thread pool. On this machine that's 16 threads. Run 100 games for statistically meaningful results — parallel execution keeps this under 10 minutes:

```bash
./battle.sh /tmp/<new_deck>.dck /tmp/<baseline_deck>.dck 100
```

The output includes throughput metrics (games/sec). If simulations are slow (< 0.5 games/sec), reduce to 50 games for faster iteration, then do a full 200-game run on the final version.

Look at:
- **Win rate and 95% CI**: Is the new deck statistically better? A 95% CI that doesn't cross 50% is significant. 100 games gives roughly ±10% — use 200 to confirm a small edge.
- **Played/Drawn ratio = empirical AI-playability**: The static `RemoveDeck` check in Phase 3.5 catches the worst cards, but it is NOT sufficient. The Card Performance Report's `Drawn`/`Played` columns are the ground truth. A card drawn many times but cast ~0 times is **dead in AI hands** even with no flag. In practice the AI reliably casts **creatures and proactive sorceries/enchantments**, but **hoards reactive instants** (mass bounce, fogs, "answer the alpha strike" cards) and rarely fires them. Prefer proactive/creature-based answers over reactive ones; cut any card with a played-rate near zero. (Example seen: Aetherspouts cast 0/37, River's Rebuke 1/28 — both replaced with finisher creatures the AI casts reliably.)
- **Card performance report scores**: Note the scorer credits Dmg/Mana/Block/Buff/Rmvl but gives **0.0 to counterspells and card draw** by design — do not rank those by score; judge them strategically.
- **Card performance report**: Which cards have low scores (bottom 10)? These are cut candidates.
- **Average turns**: Is the deck faster or slower?
- **Timeouts/Draws**: High timeout count means games are stalling — the deck may need more finishers.

Present results to the user with recommendations.

### Phase 6: Iterate

Based on simulation results:
1. Identify the 5 worst-performing cards from the Card Performance Report
2. Research replacements (repeat Phase 2-3 for candidates)
3. Propose swaps with rationale
4. If user approves, update the deck file and re-simulate

## Output Format

Present the final deck as a clear summary:

```
=== <Deck Name> ===
Commander: <name>
Strategy: <2-3 sentence summary>
Estimated budget: $XX (excluding commander + basics)

Creatures (XX):
  - Card Name ($X.XX) — reason for inclusion
  ...

Instants (XX):
  ...

[etc. by category]

Mana Base (XX lands + XX rocks):
  ...
```

Then offer:
- "Save to iPad" — upload via xcrun devicectl
- "Run simulation" — test against a baseline
- "Adjust budget" — re-optimize for different price target

## Key Reference Paths

- Card scripts: `forge-gui/res/cardsfolder/<first_letter>/<card_name>.txt`
- Edition data: `forge-gui/res/editions/<Set Name>.txt`
- Battle script: `./battle.sh <deck1> <deck2> [numGames]`
- iPad upload: `xcrun devicectl device copy to --device 00008101-001A41820113A01E --source <file> --destination "Documents/decks/commander/<name>.dck" --domain-type appDataContainer --domain-identifier com.mathforthemasses.forge.ios`
- iPad deck download: `xcrun devicectl device copy from --device 00008101-001A41820113A01E --source "Documents/decks/commander/<name>.dck" --domain-type appDataContainer --domain-identifier com.mathforthemasses.forge.ios --destination /tmp/<name>.dck`
