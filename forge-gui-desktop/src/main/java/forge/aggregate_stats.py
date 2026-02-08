#!/usr/bin/env python3
"""Aggregate card performance stats across all 33 tournament result files."""

import os
import re
import sys

results_dir = "/private/tmp/ipad-decks/results"

# Replacement cards (one per file) - we'll include them but mark them
replacement_cards = set()

# Aggregated stats: card_name -> [total_dmg, total_mana, total_block, total_buff, total_rmvl, total_drawn, total_played]
card_totals = {}
card_files_seen = {}  # card_name -> number of files it appeared in

# Parse each result file
for fname in sorted(os.listdir(results_dir)):
    if not fname.endswith(".txt") or fname == "summary.txt":
        continue

    filepath = os.path.join(results_dir, fname)

    in_report = False
    with open(filepath, "r") as f:
        for line in f:
            if "Card Performance Report" in line:
                in_report = True
                continue
            if not in_report:
                continue

            # Match data lines: "  1.  Card Name                        0.0    0.0 ..."
            m = re.match(r'^\s*\d+\.\s+(.+?)\s{2,}(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)/(\d+)\s+(\d+)/(\d+)', line)
            if not m:
                continue

            name = m.group(1).strip()
            avg_score = float(m.group(2))
            avg_dmg = float(m.group(3))
            avg_mana = float(m.group(4))
            avg_block = float(m.group(5))
            avg_buff = float(m.group(6))
            avg_rmvl = float(m.group(7))
            drawn = int(m.group(8))
            total_games = int(m.group(9))
            played = int(m.group(10))

            if drawn <= 0:
                continue

            # Reconstruct raw totals from averages
            raw_dmg = avg_dmg * drawn
            raw_mana = avg_mana * drawn
            raw_block = avg_block * drawn
            raw_buff = avg_buff * drawn
            raw_rmvl = avg_rmvl * drawn

            if name not in card_totals:
                card_totals[name] = [0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0]
                card_files_seen[name] = 0

            card_totals[name][0] += raw_dmg
            card_totals[name][1] += raw_mana
            card_totals[name][2] += raw_block
            card_totals[name][3] += raw_buff
            card_totals[name][4] += raw_rmvl
            card_totals[name][5] += drawn
            card_totals[name][6] += played
            card_totals[name][7] += total_games
            card_files_seen[name] += 1

# Calculate averages and sort by score ascending
results = []
for name, t in card_totals.items():
    drawn = t[5]
    if drawn <= 0:
        continue
    avg_dmg = t[0] / drawn
    avg_mana = t[1] / drawn
    avg_block = t[2] / drawn
    avg_buff = t[3] / drawn
    avg_rmvl = t[4] / drawn
    avg_score = avg_dmg + avg_mana + avg_block * 0.8 + avg_buff + avg_rmvl * 0.8
    played = t[6]
    total_games = t[7]
    files = card_files_seen[name]
    results.append((avg_score, name, avg_dmg, avg_mana, avg_block, avg_buff, avg_rmvl, drawn, total_games, played, files))

results.sort(key=lambda x: x[0])

total_games = 33 * 400
print(f"=== Aggregated Card Performance ({total_games:,} games across 33 tournament runs) ===")
print(f"{'Rank':>4}  {'Card Name':<32s} {'Score':>7s} {'Dmg':>6s} {'Mana':>6s} {'Block':>6s} {'Buff':>6s} {'Rmvl':>6s} {'Drawn':>10s} {'Played':>10s} {'Runs':>4s}")

for i, (score, name, dmg, mana, block, buff, rmvl, drawn, tg, played, files) in enumerate(results, 1):
    display_name = name[:32] if len(name) <= 32 else name[:29] + "..."
    # Show drawn as fraction of total games, played as fraction of drawn
    print(f"{i:4d}. {display_name:<32s} {score:7.1f} {dmg:6.1f} {mana:6.1f} {block:6.1f} {buff:6.1f} {rmvl:6.1f} {drawn:5d}/{tg:<5d} {played:5d}/{drawn:<5d} {files:4d}")
