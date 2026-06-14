package forge.game;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.game.event.GameEvent;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventGameOutcome;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * Session-cumulative tracker for HUMAN games. Mirrors DeckBattler's conditional win-rate report
 * (per-card Drawn / Win%Drawn / Win%NotDr / dWin%) so a human's piloting can be compared
 * apples-to-apples with the AI's on the same deck.
 *
 * A card's marginal effect = Win%Drawn - Win%NotDr (within-run paired); the value must be read
 * RELATIVE to the deck-average dWin, because drawing any card correlates with game length.
 *
 * Gated by -Dforge.trackHuman=true. One instance is subscribed per human Player at game creation
 * (see HostedMatch.startGame). The accumulator is static so multiple games in a session aggregate.
 * Output path: -Dforge.trackHuman.out=&lt;file&gt; (default "human-play-report.txt" in the working dir),
 * rewritten with the cumulative report at the end of each game; a one-line summary also goes to stdout.
 */
public class HumanPlayTracker {
    public static final String ENABLE_PROPERTY = "forge.trackHuman";
    public static final String OUTPUT_PROPERTY = "forge.trackHuman.out";

    public static boolean isEnabled() {
        return "true".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY));
    }

    // Session-cumulative across all tracked games. [0]=games drawn in, [1]=games won when drawn.
    private static final Map<String, int[]> CUMULATIVE = new LinkedHashMap<String, int[]>();
    private static int totalGames = 0;
    private static int totalWins = 0;
    private static final Object LOCK = new Object();

    private final Player tracked;
    private final Set<String> drawnThisGame = new HashSet<String>();
    private boolean recorded = false; // guards against the outcome event firing more than once

    public HumanPlayTracker(final Player trackedPlayer) {
        this.tracked = trackedPlayer;
    }

    @Subscribe
    public void receive(final GameEvent ev) {
        if (ev instanceof GameEventCardChangeZone) {
            handleZoneChange((GameEventCardChangeZone) ev);
        } else if (ev instanceof GameEventGameOutcome) {
            handleOutcome((GameEventGameOutcome) ev);
        }
    }

    /** A card moving Library -> Hand for the tracked player counts as "drawn this game". */
    private void handleZoneChange(final GameEventCardChangeZone ev) {
        final Zone from = ev.from();
        final Zone to = ev.to();
        if (from == null || to == null || ev.card() == null) {
            return;
        }
        if (from.getZoneType() == ZoneType.Library && to.getZoneType() == ZoneType.Hand
                && ev.card().getOwner() != null && ev.card().getOwner().equals(tracked)) {
            drawnThisGame.add(ev.card().getName());
        }
    }

    private void handleOutcome(final GameEventGameOutcome ev) {
        if (recorded || ev.result() == null) {
            return;
        }
        recorded = true;
        final GameOutcome outcome = ev.result();
        final boolean won = !outcome.isDraw()
                && outcome.getWinningPlayer() == tracked.getRegisteredPlayer();

        synchronized (LOCK) {
            totalGames++;
            if (won) {
                totalWins++;
            }
            for (final String name : drawnThisGame) {
                int[] c = CUMULATIVE.get(name);
                if (c == null) {
                    c = new int[2];
                    CUMULATIVE.put(name, c);
                }
                c[0]++;            // games this card was drawn
                if (won) {
                    c[1]++;        // games won among those where it was drawn
                }
            }
            writeReport(won);
        }
    }

    /** Rewrites the cumulative report (file + a one-line stdout summary). Caller holds LOCK. */
    private void writeReport(final boolean won) {
        final StringBuilder sb = new StringBuilder();
        final double overall = totalGames > 0 ? 100.0 * totalWins / totalGames : 0.0;
        sb.append("=== Card Performance Report (").append(totalGames)
          .append(" games, player: ").append(tracked.getName()).append(") [HUMAN] ===\n");
        sb.append(String.format("Overall player record: %d/%d (%.1f%%)"
                + "  [Win%%Drawn vs Win%%NotDr isolates each card; read RELATIVE to deck-avg dWin]%n",
                totalWins, totalGames, overall));
        sb.append(String.format("%-32s %7s %10s %10s %8s%n",
                "Card", "Drawn", "Win%Drawn", "Win%NotDr", "dWin%"));

        final List<Map.Entry<String, int[]>> rows =
                new ArrayList<Map.Entry<String, int[]>>(CUMULATIVE.entrySet());
        Collections.sort(rows, new Comparator<Map.Entry<String, int[]>>() {
            @Override
            public int compare(final Map.Entry<String, int[]> a, final Map.Entry<String, int[]> b) {
                return Double.compare(dWinSort(b.getValue()), dWinSort(a.getValue())); // best first
            }
        });

        for (final Map.Entry<String, int[]> e : rows) {
            final int drawn = e.getValue()[0];
            final int winsDrawn = e.getValue()[1];
            final int notDrawn = totalGames - drawn;
            final int winsNotDrawn = totalWins - winsDrawn;
            final Double wpDrawn = drawn > 0 ? 100.0 * winsDrawn / drawn : null;
            final Double wpNotDrawn = notDrawn > 0 ? 100.0 * winsNotDrawn / notDrawn : null;
            final Double dWin = (wpDrawn != null && wpNotDrawn != null) ? wpDrawn - wpNotDrawn : null;
            sb.append(String.format("%-32s %5d/%-2d %9s %9s %7s%n",
                    truncate(e.getKey()), drawn, totalGames,
                    wpDrawn != null ? String.format("%.1f%%", wpDrawn) : "n/a",
                    wpNotDrawn != null ? String.format("%.1f%%", wpNotDrawn) : "n/a",
                    dWin != null ? String.format("%+.1f", dWin) : "n/a"));
        }

        final String report = sb.toString();
        final String out = System.getProperty(OUTPUT_PROPERTY, "human-play-report.txt");
        try {
            final PrintWriter pw = new PrintWriter(new FileWriter(new File(out), false));
            try {
                pw.print(report);
            } finally {
                pw.close();
            }
        } catch (final Exception ex) {
            System.err.println("HumanPlayTracker: could not write " + out + ": " + ex.getMessage());
        }
        System.out.println("[HumanPlayTracker] game " + totalGames + " recorded ("
                + (won ? "WIN" : "loss") + "), record " + totalWins + "/" + totalGames
                + " -> " + out);
    }

    private static double dWinSort(final int[] c) {
        // sort key only; undefined subsets sort to the bottom
        final int drawn = c[0];
        if (drawn <= 0 || drawn >= totalGames) {
            return -1000.0;
        }
        final double wpDrawn = 100.0 * c[1] / drawn;
        final double wpNot = 100.0 * (totalWins - c[1]) / (totalGames - drawn);
        return wpDrawn - wpNot;
    }

    private static String truncate(final String name) {
        return name.length() > 32 ? name.substring(0, 29) + "..." : name;
    }
}
