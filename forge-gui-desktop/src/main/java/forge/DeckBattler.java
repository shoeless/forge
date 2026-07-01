package forge;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.function.Function;

/**
 * Headless AI vs AI deck testing tool.
 *
 * Usage: java -cp <classpath> forge.DeckBattler <deck1.dck> <deck2.dck> [numGames] [timeoutSec] [threads]
 */
public class DeckBattler {

    /**
     * A PrintStream wrapper that prepends a per-thread game tag to output.
     * This allows filtering log output by game number (e.g., grep "[G042]")
     * without modifying any of the existing System.out.println call sites.
     */
    static class GameTaggingPrintStream extends PrintStream {
        private final ThreadLocal<String> gameTag = new ThreadLocal<String>();
        private final PrintStream underlying;

        GameTaggingPrintStream(PrintStream out) {
            super(out, true);
            this.underlying = out;
        }

        void setGameTag(String tag) {
            gameTag.set(tag);
        }

        void clearGameTag() {
            gameTag.remove();
        }

        private String prependTag(String s) {
            String tag = gameTag.get();
            if (tag != null) {
                return tag + " " + s;
            }
            return s;
        }

        @Override
        public void println(String x) {
            String line = prependTag(x);
            synchronized (underlying) {
                underlying.println(line);
            }
        }

        @Override
        public void println(Object x) {
            String line = prependTag(String.valueOf(x));
            synchronized (underlying) {
                underlying.println(line);
            }
        }

        @Override
        public void println() {
            synchronized (underlying) {
                underlying.println();
            }
        }

        @Override
        public void print(String s) {
            String line = prependTag(s);
            synchronized (underlying) {
                underlying.print(line);
            }
        }

        @Override
        public void print(Object obj) {
            String line = prependTag(String.valueOf(obj));
            synchronized (underlying) {
                underlying.print(line);
            }
        }

        @Override
        public PrintStream printf(String format, Object... args) {
            String line = prependTag(String.format(format, args));
            synchronized (underlying) {
                underlying.print(line);
            }
            return this;
        }

        @Override
        public void flush() {
            underlying.flush();
        }
    }

    /** Result of a single game. */
    static class GameResult {
        final int gameNumber;
        final int winner; // 1, 2, or 0 for draw
        final boolean timeout;
        final int turns;

        GameResult(int gameNumber, int winner, boolean timeout, int turns) {
            this.gameNumber = gameNumber;
            this.winner = winner;
            this.timeout = timeout;
            this.turns = turns;
        }
    }

    /**
     * Thread-safe aggregator that collects card performance stats across all games.
     * Array: [damageDealt, manaCredit, blockingDamage, buffCredit, gamesSeen, gamesPlayed, removalValue]
     */
    static class CardPerformanceAggregator {
        private final ConcurrentHashMap<String, double[]> cumulativeStats =
                new ConcurrentHashMap<String, double[]>();
        // Overall player-1 record, for conditional (drawn vs not-drawn) win-rate analysis.
        private int totalGamesCounted = 0;
        private int totalP1Wins = 0;

        // Length-stratified dWin: game length is a confounder (longer games draw more cards AND a
        // tempo deck loses them), biasing every card's dWin negative. We stratify games by turn-count
        // bucket and Mantel-Haenszel-average the within-bucket risk differences to de-confound.
        // Upper-inclusive turn boundaries; index BUCKET_BOUNDS.length is the open-ended "and up" bucket.
        private static final int[] BUCKET_BOUNDS = {8, 11, 14, 17, 20, 24, 28};
        private static final int NBUCKETS = BUCKET_BOUNDS.length + 1;
        private final int[] bucketTotal = new int[NBUCKETS];   // games per length bucket
        private final int[] bucketWins = new int[NBUCKETS];    // p1 wins per length bucket
        private final Map<String, int[]> bucketDrawn = new HashMap<String, int[]>();     // card -> drawn count per bucket
        private final Map<String, int[]> bucketWinsDrawn = new HashMap<String, int[]>(); // card -> wins-when-drawn per bucket

        private static int bucketOf(int turns) {
            for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
                if (turns <= BUCKET_BOUNDS[i]) {
                    return i;
                }
            }
            return BUCKET_BOUNDS.length;
        }

        synchronized void addGameResult(Map<String, CardPerformanceTracker.CardStats> gameStats,
                boolean p1Won, int turns) {
            totalGamesCounted++;
            if (p1Won) {
                totalP1Wins++;
            }
            // Stratify only decided games with a real length; timeouts/errors pass turns<=0 (skip).
            final int bucket = turns > 0 ? bucketOf(turns) : -1;
            if (bucket >= 0) {
                bucketTotal[bucket]++;
                if (p1Won) {
                    bucketWins[bucket]++;
                }
            }
            for (Map.Entry<String, CardPerformanceTracker.CardStats> entry : gameStats.entrySet()) {
                String cardName = entry.getKey();
                CardPerformanceTracker.CardStats stats = entry.getValue();

                double[] cumulative = cumulativeStats.get(cardName);
                if (cumulative == null) {
                    cumulative = new double[8];
                    cumulativeStats.put(cardName, cumulative);
                }
                cumulative[0] += stats.damageDealt;
                cumulative[1] += stats.manaCredit;
                cumulative[2] += stats.blockingDamage;
                cumulative[3] += stats.buffCredit;
                cumulative[4] += 1;            // games this card was DRAWN into hand
                if (stats.played) {
                    cumulative[5] += 1;
                }
                cumulative[6] += stats.removalValue;
                if (p1Won) {
                    cumulative[7] += 1;        // games WON among those where this card was drawn
                }
                if (bucket >= 0) {
                    int[] bd = bucketDrawn.get(cardName);
                    if (bd == null) {
                        bd = new int[NBUCKETS];
                        bucketDrawn.put(cardName, bd);
                    }
                    bd[bucket]++;
                    if (p1Won) {
                        int[] bw = bucketWinsDrawn.get(cardName);
                        if (bw == null) {
                            bw = new int[NBUCKETS];
                            bucketWinsDrawn.put(cardName, bw);
                        }
                        bw[bucket]++;
                    }
                }
            }
        }

        /**
         * Length-stratified (de-confounded) dWin in percentage points, or null if no usable strata.
         * Mantel-Haenszel weighted average of within-bucket (Win%Drawn - Win%NotDrawn); weight =
         * drawn*notDrawn/bucketTotal. Holding game length fixed removes the long-game bias that
         * pushes raw dWin negative for every card.
         */
        Double stratifiedDWin(String card) {
            int[] bd = bucketDrawn.get(card);
            if (bd == null) {
                return null;
            }
            int[] bw = bucketWinsDrawn.get(card);
            double num = 0.0;
            double den = 0.0;
            for (int b = 0; b < NBUCKETS; b++) {
                int drawn = bd[b];
                int notDrawn = bucketTotal[b] - drawn;
                if (drawn <= 0 || notDrawn <= 0) {
                    continue;
                }
                int winsDrawn = bw == null ? 0 : bw[b];
                int winsNot = bucketWins[b] - winsDrawn;
                double p1 = (double) winsDrawn / drawn;
                double p0 = (double) winsNot / notDrawn;
                double w = (double) drawn * notDrawn / bucketTotal[b];
                num += w * (p1 - p0) * 100.0;
                den += w;
            }
            return den > 0 ? num / den : null;
        }

        void printReport(PrintStream out, int totalGames, String playerName, String opponentName) {
            if (cumulativeStats.isEmpty()) {
                out.println("No card performance data collected.");
                return;
            }

            // Build sorted list of entries by average score ascending (worst first)
            List<Map.Entry<String, double[]>> entries =
                    new ArrayList<Map.Entry<String, double[]>>(cumulativeStats.entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<String, double[]>>() {
                @Override
                public int compare(Map.Entry<String, double[]> a, Map.Entry<String, double[]> b) {
                    double scoreA = avgScore(a.getValue());
                    double scoreB = avgScore(b.getValue());
                    return Double.compare(scoreA, scoreB);
                }
            });

            out.println();
            out.println("=== Card Performance Report (" + totalGames + " games, player: " + playerName + ") ===");
            out.println("Overall player record: " + totalP1Wins + "/" + totalGamesCounted
                    + String.format(" (%.1f%%)", totalGamesCounted > 0 ? 100.0 * totalP1Wins / totalGamesCounted : 0.0)
                    + "  [Win%Drawn vs Win%NotDr isolates each card's effect; NotDr should ~= control]");
            out.println(String.format("%-4s  %-32s %9s %6s %6s %6s %6s %6s %7s %6s %9s %9s %7s",
                    "Rank", "Card Name", "Avg Score", "Dmg", "Mana", "Block", "Buff", "Rmvl", "Drawn", "Played",
                    "Win%Drawn", "Win%NotDr", "dWin%"));

            int rank = 1;
            for (Map.Entry<String, double[]> entry : entries) {
                String name = entry.getKey();
                double[] c = entry.getValue();
                double games = c[4];
                if (games <= 0) {
                    continue;
                }
                double avgDmg = c[0] / games;
                double avgMana = c[1] / games;
                double avgBlock = c[2] / games;
                double avgBuff = c[3] / games;
                double avgRmvl = c[6] / games;
                double avgScr = avgScore(c);
                int drawn = (int) c[4];
                int played = (int) c[5];
                int winsDrawn = (int) c[7];
                int notDrawn = totalGamesCounted - drawn;
                int winsNotDrawn = totalP1Wins - winsDrawn;
                // Conditional win rates are UNDEFINED (null) when there are no games in that subset:
                // never drawn -> win%|drawn is n/a; drawn every game -> win%|not-drawn is n/a.
                Double wpDrawn = drawn > 0 ? 100.0 * winsDrawn / drawn : null;
                Double wpNotDrawn = notDrawn > 0 ? 100.0 * winsNotDrawn / notDrawn : null;
                Double dWin = (wpDrawn != null && wpNotDrawn != null) ? wpDrawn - wpNotDrawn : null;
                String cDrawn = String.format("%9s", wpDrawn != null ? String.format("%.1f%%", wpDrawn) : "n/a");
                String cNotDr = String.format("%9s", wpNotDrawn != null ? String.format("%.1f%%", wpNotDrawn) : "n/a");
                String cDelta = String.format("%7s", dWin != null ? String.format("%+.1f", dWin) : "n/a");

                // Truncate long card names
                if (name.length() > 32) {
                    name = name.substring(0, 29) + "...";
                }

                out.println(String.format("%3d.  %-32s %9.1f %6.1f %6.1f %6.1f %6.1f %6.1f %4d/%-2d %4d/%-2d %s %s %s",
                        rank, name, avgScr, avgDmg, avgMana, avgBlock, avgBuff, avgRmvl,
                        drawn, totalGames, played, drawn, cDrawn, cNotDr, cDelta));
                rank++;
            }

            // ---- Tier 1 cut-screening: deck-average dWin baseline (removes the negative
            // confound that drawing ANY card lengthens games), adjusted dWin, and a 95% CI
            // on each card's dWin so noise reads (like Spark Double) don't get cut. ----
            double sumDWin = 0;
            int nDWin = 0;
            for (Map.Entry<String, double[]> entry : entries) {
                double[] c = entry.getValue();
                int drawn = (int) c[4];
                int notDrawn = totalGamesCounted - drawn;
                if (drawn > 0 && notDrawn > 0) {
                    double dWin = 100.0 * c[7] / drawn - 100.0 * (totalP1Wins - c[7]) / notDrawn;
                    sumDWin += dWin;
                    nDWin++;
                }
            }
            final double deckAvgDWin = nDWin > 0 ? sumDWin / nDWin : 0.0;

            out.println();
            out.println(String.format(
                    "Deck-average dWin (confound baseline) = %+.2f%% over %d cards. "
                    + "Drawing ANY card biases dWin negative; read each card RELATIVE to this.",
                    deckAvgDWin, nDWin));
            out.println("=== CUT SCREENING (AdjDWin = dWin - deck-avg; worst-first; "
                    + "◄cut = adj-dWin upper 95% CI < 0) ===");
            out.println(String.format("%-4s  %-32s %6s %6s %10s %10s %8s %8s %8s %8s  %s",
                    "Rank", "Card Name", "Drawn", "Cast%", "Win%Drawn", "Win%NotDr",
                    "dWin", "AdjDWin", "StratDW", "+-95CI", "Flag"));

            List<Map.Entry<String, double[]>> screen =
                    new ArrayList<Map.Entry<String, double[]>>(cumulativeStats.entrySet());
            final int tWins = totalP1Wins;
            final int tGames = totalGamesCounted;
            Collections.sort(screen, new Comparator<Map.Entry<String, double[]>>() {
                @Override
                public int compare(Map.Entry<String, double[]> a, Map.Entry<String, double[]> b) {
                    return Double.compare(dWinOrInf(a.getValue(), tWins, tGames),
                            dWinOrInf(b.getValue(), tWins, tGames));
                }
            });
            int srank = 1;
            for (Map.Entry<String, double[]> entry : screen) {
                double[] c = entry.getValue();
                int drawn = (int) c[4];
                int notDrawn = totalGamesCounted - drawn;
                if (drawn <= 0 || notDrawn <= 0) {
                    continue;
                }
                int winsDrawn = (int) c[7];
                int winsNot = totalP1Wins - winsDrawn;
                double pD = (double) winsDrawn / drawn;
                double pN = (double) winsNot / notDrawn;
                double dWin = 100.0 * (pD - pN);
                double adj = dWin - deckAvgDWin;
                // 1.96 * SE(difference of two proportions) * 100
                double margin = 196.0 * Math.sqrt(pD * (1 - pD) / drawn + pN * (1 - pN) / notDrawn);
                int played = (int) c[5];
                double cast = 100.0 * played / drawn;
                // Only flag when the normal approximation for a difference of proportions is
                // valid (success-failure condition: >=5 in every cell). Otherwise tiny-n
                // extremes (e.g. 2 wins in 10 draws) spuriously look "significant" -- exactly
                // how a 40-game sample flagged Aether Gale, our best card.
                boolean validApprox = winsDrawn >= 5 && (drawn - winsDrawn) >= 5
                        && winsNot >= 5 && (notDrawn - winsNot) >= 5;
                boolean cut = validApprox && (adj + margin) < 0;
                Double strat = stratifiedDWin(entry.getKey());
                String stratStr = strat != null ? String.format("%+.1f", strat) : "n/a";
                String nm = entry.getKey();
                if (nm.length() > 32) {
                    nm = nm.substring(0, 29) + "...";
                }
                out.println(String.format(
                        "%3d.  %-32s %6d %5.0f%% %9.1f%% %9.1f%% %+7.1f %+7.1f %8s %7.1f  %s",
                        srank, nm, drawn, cast, 100.0 * pD, 100.0 * pN, dWin, adj, stratStr, margin,
                        cut ? "◄cut" : ""));
                srank++;
            }

            // ---- machine-readable rows for exact cross-matchup/run pooling (screen-cuts.py).
            // Pooling raw counts avoids the rounding error of re-deriving from printed %s. ----
            out.println("=== CUT SCREENING DATA (machine-readable) ===");
            for (Map.Entry<String, double[]> entry : cumulativeStats.entrySet()) {
                double[] c = entry.getValue();
                int drawn = (int) c[4];
                int winsDrawn = (int) c[7];
                int notDrawn = totalGamesCounted - drawn;
                int winsNot = totalP1Wins - winsDrawn;
                int played = (int) c[5];
                out.println(String.format("#CUTROW\t%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d",
                        playerName, opponentName, entry.getKey(),
                        drawn, winsDrawn, notDrawn, winsNot, played, totalGamesCounted));
            }

            // Per-length-bucket counts for pooled Mantel-Haenszel stratified dWin (screen-cuts.py).
            for (int b = 0; b < NBUCKETS; b++) {
                out.println(String.format("#BUCKETTOT\t%s\t%s\t%d\t%d\t%d",
                        playerName, opponentName, b, bucketTotal[b], bucketWins[b]));
            }
            for (Map.Entry<String, int[]> be : bucketDrawn.entrySet()) {
                int[] bd = be.getValue();
                int[] bw = bucketWinsDrawn.get(be.getKey());
                for (int b = 0; b < NBUCKETS; b++) {
                    if (bd[b] > 0) {
                        out.println(String.format("#CUTBUCKET\t%s\t%s\t%s\t%d\t%d\t%d",
                                playerName, opponentName, be.getKey(), b, bd[b],
                                bw == null ? 0 : bw[b]));
                    }
                }
            }
        }

        /** dWin% for sorting (worst first); undefined subsets sort to the end. */
        private static double dWinOrInf(double[] c, int tWins, int tGames) {
            int drawn = (int) c[4];
            int notDrawn = tGames - drawn;
            if (drawn <= 0 || notDrawn <= 0) {
                return Double.POSITIVE_INFINITY;
            }
            double pD = (double) c[7] / drawn;
            double pN = (double) (tWins - c[7]) / notDrawn;
            return 100.0 * (pD - pN);
        }

        private static double avgScore(double[] c) {
            double games = c[4];
            if (games <= 0) {
                return 0;
            }
            return (c[0] + c[1] + c[2] * 0.8 + c[3] + c[6] * 0.8) / games;
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: forge.DeckBattler <deck1.dck> <deck2.dck> [numGames] [timeoutSec] [threads]");
            System.out.println("  numGames:   Number of games to play (default: 100)");
            System.out.println("  timeoutSec: Per-game timeout in seconds (default: 120)");
            System.out.println("  threads:    Number of parallel threads (default: available processors)");
            System.exit(1);
        }

        File deck1File = new File(args[0]);
        File deck2File = new File(args[1]);
        int numGames = args.length >= 3 ? Integer.parseInt(args[2]) : 100;
        int timeoutSec = args.length >= 4 ? Integer.parseInt(args[3]) : 120;
        int threadCount = args.length >= 5 ? Integer.parseInt(args[4]) : Runtime.getRuntime().availableProcessors();

        if (!deck1File.exists()) {
            System.err.println("Deck file not found: " + deck1File.getAbsolutePath());
            System.exit(1);
        }
        if (!deck2File.exists()) {
            System.err.println("Deck file not found: " + deck2File.getAbsolutePath());
            System.exit(1);
        }

        // Initialize Forge engine
        System.out.print("Initializing Forge engine... ");
        long initStart = System.currentTimeMillis();
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, new Function<ForgePreferences, Void>() {
            @Override
            public Void apply(ForgePreferences preferences) {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            }
        });
        long initTime = System.currentTimeMillis() - initStart;
        System.out.println("done (" + String.format("%.1f", initTime / 1000.0) + "s)");

        // Load decks and detect format
        Deck deck1 = DeckSerializer.fromFile(deck1File);
        Deck deck2 = DeckSerializer.fromFile(deck2File);

        boolean isCommander = deck1.has(DeckSection.Commander) || deck2.has(DeckSection.Commander);

        final String name1 = deck1.getName();
        final String name2 = deck2.getName();
        int cards1 = deck1.getMain().countAll();
        int cards2 = deck2.getMain().countAll();

        // Card performance aggregator for deck 1
        final CardPerformanceAggregator aggregator = new CardPerformanceAggregator();

        String format = isCommander ? "Commander" : "Constructed";
        System.out.println("Format: " + format);
        System.out.println("Deck 1: " + name1 + " (" + cards1 + " cards)");
        System.out.println("Deck 2: " + name2 + " (" + cards2 + " cards)");
        System.out.println("Running " + numGames + " games with " + threadCount + " threads (timeout: " + timeoutSec + "s each)...");

        // Install game-tagging PrintStreams for both stdout and stderr
        final GameTaggingPrintStream taggedOut = new GameTaggingPrintStream(System.out);
        final GameTaggingPrintStream taggedErr = new GameTaggingPrintStream(System.err);
        System.setOut(taggedOut);
        System.setErr(taggedErr);

        // Result tracking
        final AtomicInteger gamesCompleted = new AtomicInteger(0);
        int wins1 = 0;
        int wins2 = 0;
        int draws = 0;
        int timeouts = 0;
        int totalTurns = 0;

        final int maxTurns = 200;
        long startTime = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CompletionService<GameResult> completionService = new ExecutorCompletionService<GameResult>(pool);

        // Submit all games
        for (int i = 1; i <= numGames; i++) {
            final int gameNumber = i;
            final boolean cmdFormat = isCommander;
            final int timeout = timeoutSec;

            completionService.submit(new Callable<GameResult>() {
                @Override
                public GameResult call() {
                    String tag = "[G" + String.format("%03d", gameNumber) + "]";
                    taggedOut.setGameTag(tag);
                    taggedErr.setGameTag(tag);
                    taggedErr.underlying.println(tag + " started on " + Thread.currentThread().getName());
                    try {
                        return runSingleGame(gameNumber, deck1File, deck2File, name1, name2,
                                cmdFormat, timeout, maxTurns, aggregator);
                    } finally {
                        taggedOut.clearGameTag();
                        taggedErr.clearGameTag();
                    }
                }
            });
        }

        // Collect results as they complete
        for (int i = 0; i < numGames; i++) {
            try {
                Future<GameResult> future = completionService.take();
                GameResult result = future.get();
                int completed = gamesCompleted.incrementAndGet();

                if (result.timeout) {
                    timeouts++;
                    draws++;
                } else if (result.winner == 1) {
                    wins1++;
                } else if (result.winner == 2) {
                    wins2++;
                } else {
                    draws++;
                }
                if (result.turns > 0) {
                    totalTurns += result.turns;
                }

                // Progress output every 10 games or at the end
                if (completed % 10 == 0 || completed == numGames) {
                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                    double gamesPerSec = completed / elapsed;
                    // Use \r for single-line progress updates on stderr to avoid mixing with tagged stdout
                    taggedErr.underlying.print("\r  [" + completed + "/" + numGames + "] "
                            + name1 + ": " + wins1 + "  " + name2 + ": " + wins2
                            + "  (" + String.format("%.1f", gamesPerSec) + " games/s)    ");
                }
            } catch (Exception e) {
                int completed = gamesCompleted.incrementAndGet();
                draws++;
                taggedErr.underlying.println("Game error: " + e.getMessage());
            }
        }

        pool.shutdown();
        taggedErr.underlying.println(); // newline after progress

        double totalTime = (System.currentTimeMillis() - startTime) / 1000.0;
        int completedGames = gamesCompleted.get();

        // Print summary (use taggedOut.underlying to bypass tagging)
        taggedOut.underlying.println();
        taggedOut.underlying.println("=== Results: " + completedGames + " games in " + String.format("%.1f", totalTime) + "s (" + threadCount + " threads) ===");

        int decidedGames = wins1 + wins2;
        double winRate1 = decidedGames > 0 ? (100.0 * wins1 / decidedGames) : 0;

        taggedOut.underlying.println("  " + name1 + " wins: " + wins1
                + " (" + String.format("%.1f", 100.0 * wins1 / completedGames) + "%)");
        taggedOut.underlying.println("  " + name2 + " wins: " + wins2
                + " (" + String.format("%.1f", 100.0 * wins2 / completedGames) + "%)");
        taggedOut.underlying.println("  Draws: " + draws + "   Timeouts: " + timeouts);

        if (decidedGames > 0) {
            // 95% confidence interval using Wilson score interval
            double z = 1.96;
            double p = (double) wins1 / decidedGames;
            double n = decidedGames;
            double denominator = 1 + z * z / n;
            double center = (p + z * z / (2 * n)) / denominator;
            double margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n) / denominator;
            double lower = Math.max(0, center - margin) * 100;
            double upper = Math.min(1, center + margin) * 100;

            taggedOut.underlying.println("  Win rate: " + String.format("%.1f", winRate1)
                    + "% [95% CI: " + String.format("%.1f", lower)
                    + "% - " + String.format("%.1f", upper) + "%]");
        }

        if (decidedGames > 0 && totalTurns > 0) {
            taggedOut.underlying.println("  Average turns: "
                    + String.format("%.1f", (double) totalTurns / decidedGames));
        }

        taggedOut.underlying.println("  Throughput: " + String.format("%.1f", completedGames / totalTime) + " games/s");

        // Print card performance report for deck 1
        aggregator.printReport(taggedOut.underlying, completedGames, name1, name2);
    }

    /**
     * Runs a single game and returns the result. Designed to be called from any thread.
     */
    private static GameResult runSingleGame(int gameNumber, File deck1File, File deck2File,
            String name1, String name2, boolean isCommander, int timeoutSec, int maxTurns,
            CardPerformanceAggregator aggregator) {
        // Load fresh deck copies (DeckSerializer reads from files, thread-safe)
        Deck d1 = DeckSerializer.fromFile(deck1File);
        Deck d2 = DeckSerializer.fromFile(deck2File);

        List<RegisteredPlayer> players = new ArrayList<RegisteredPlayer>();
        if (isCommander) {
            players.add(RegisteredPlayer.forCommander(d1).setPlayer(new LobbyPlayerAi(name1, null)));
            players.add(RegisteredPlayer.forCommander(d2).setPlayer(new LobbyPlayerAi(name2, null)));
        } else {
            players.add(new RegisteredPlayer(d1).setPlayer(new LobbyPlayerAi(name1, null)));
            players.add(new RegisteredPlayer(d2).setPlayer(new LobbyPlayerAi(name2, null)));
        }

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        if (isCommander) {
            rules.addAppliedVariant(GameType.Commander);
        }
        Match match = new Match(rules, players, "DeckBattler");
        Game game = match.createGame();

        // Set up card performance tracker for player 1
        Player trackedPlayer = null;
        for (Player p : game.getPlayers()) {
            if (p.getName().equals(name1)) {
                trackedPlayer = p;
                break;
            }
        }
        CardPerformanceTracker tracker = null;
        if (trackedPlayer != null) {
            tracker = new CardPerformanceTracker(trackedPlayer);
            game.subscribeToEvents(tracker);
        }

        // Set up timeout timer
        final Game gameRef = game;
        Timer timer = new Timer(true);
        final boolean[] timedOut = {false};
        // Deterministic mode (-Dforge.rngSeed): the wall-clock timeout is a seed-leak. At smaller
        // heaps, GC pauses inflate wall-time and abort borderline games past the deadline, flipping
        // the #GAMEEND row nondeterministically (the 4g-diverges/8g-identical signature). Skip arming
        // it; the turn-count monitor below (maxTurns) is the GC-independent stop. Sibling of
        // Game.canUseTimeout(). Real/GUI play never sets forge.rngSeed, so it is unaffected.
        final boolean deterministicRng = System.getProperty("forge.rngSeed") != null;
        if (!deterministicRng) {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (!gameRef.isGameOver()) {
                        timedOut[0] = true;
                        gameRef.setAge(GameStage.GameOver);
                    }
                }
            }, timeoutSec * 1000L);
        }

        // Set up turn limit monitor
        final int maxTurnLimit = maxTurns;
        final boolean[] turnLimitHit = {false};
        Thread turnMonitor = new Thread(new Runnable() {
            @Override
            public void run() {
                int lastTurn = -1;
                long lastProgressMs = System.currentTimeMillis();
                // No-progress detector: if the turn number doesn't advance for this long, signal game-over.
                // Determinism-safe: a stuck game freezes at the same state (same turn, same draws consumed) in
                // every run, so this signals at the same game state run-to-run (only the wall-clock instant
                // differs); a normal turn advances within seconds, so a slow-but-progressing game never trips the
                // generous threshold (unlike a fixed wall-clock deadline — this is progress-based).
                // LIMITATION (verified against the ghired/kaalia convergence hang): setAge(GameOver) only stops a
                // game that polls isGameOver between actions. A tight non-converging checkStaticAbilities loop on
                // the game thread does NOT check it, so this will NOT break that hang — those need a harness-level
                // JVM-kill watchdog (the gauntlet scripts wrap each run with one) or an engine iteration cap.
                final long noProgressAbortMs = 180000L;
                while (!gameRef.isGameOver()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (gameRef.getPhaseHandler() != null) {
                            final int turn = gameRef.getPhaseHandler().getTurn();
                            if (turn != lastTurn) {
                                lastTurn = turn;
                                lastProgressMs = System.currentTimeMillis();
                            }
                            if (turn > maxTurnLimit) {
                                turnLimitHit[0] = true;
                                gameRef.setAge(GameStage.GameOver);
                            } else if (!deterministicRng
                                    && System.currentTimeMillis() - lastProgressMs > noProgressAbortMs) {
                                // The WALL-CLOCK no-progress abort is a seed-leak: a slow-but-PROGRESSING turn
                                // (e.g. a token-copy storm + wide-board AI eval taking >180s for one turn) trips it
                                // at a real-time-variable instant, and setAge(GameOver) from this monitor thread is
                                // seen by the game thread after a run-to-run-VARIABLE number of trailing stack
                                // resolutions -> nondeterministic ids downstream. Skip it under -Dforge.rngSeed
                                // (sibling of the wall-clock Timer above); the turn-count limit + the gauntlet's
                                // JVM-kill watchdog are the deterministic / GC-independent stops there.
                                System.out.println("[STUCK] game aborted: turn " + turn + " made no progress for "
                                        + (noProgressAbortMs / 1000) + "s (likely an engine continuous-effect loop)");
                                turnLimitHit[0] = true;
                                gameRef.setAge(GameStage.GameOver);
                            }
                        }
                    } catch (Exception e) {
                        // Ignore - game state may be in transition
                    }
                }
            }
        });
        turnMonitor.setDaemon(true);
        turnMonitor.start();

        // Run the game
        try {
            match.startGame(game);
        } catch (Exception e) {
            System.err.println("  Game " + gameNumber + " error: " + e.getMessage());
            timer.cancel();
            if (tracker != null) {
                aggregator.addGameResult(tracker.getStats(), false, 0);
            }
            return new GameResult(gameNumber, 0, false, 0);
        }

        timer.cancel();

        // Read outcome FIRST, so this game's win/loss can be attributed to player 1's drawn cards.
        GameOutcome outcome = game.getOutcome();
        boolean nonResult = timedOut[0] || turnLimitHit[0];
        int winnerNum = 0;
        int turns = 0;
        if (!nonResult && outcome != null && !outcome.isDraw()) {
            RegisteredPlayer winner = outcome.getWinningPlayer();
            if (winner != null && winner.getPlayer().getName().equals(name1)) {
                winnerNum = 1;
            } else if (winner != null) {
                winnerNum = 2;
            }
            turns = outcome.getLastTurnNumber() > 0 ? outcome.getLastTurnNumber() : 0;
        }

        // Diagnostic (temp): dump turn-by-turn game log for one game (-Dforge.dumpGameLogGame=N)
        String dumpG = System.getProperty("forge.dumpGameLogGame");
        if (dumpG != null && ("ALL".equalsIgnoreCase(dumpG) || dumpG.equals(String.valueOf(gameNumber)))) {
            for (Object e : game.getGameLog().getLogEntries(null)) {
                System.out.println("[GLOG" + gameNumber + "] " + e);
            }
        }

        // Collect card performance data, tagging the game as a player-1 win or not.
        if (tracker != null) {
            aggregator.addGameResult(tracker.getStats(), winnerNum == 1, turns);
        }

        // Flight recorder (Wald): emit the terminal state of every game so LOSSES can be
        // analyzed, not just averages — counterspells stranded in hand at death (AI decision
        // failures), the opponent threats that finished us, and land-drop consistency.
        // Format: #GAMEEND deck opp gameNum result(W/L/T) turns ourTurns ourLandDrops
        //         countersInHand topOppCreatures(;-joined)
        if (tracker != null) {
            try {
                // getRegisteredPlayers, NOT getPlayers: an eliminated (= losing) player is
                // removed from getPlayers(), and losses are the games this recorder is FOR.
                Player p1 = null;
                for (Player p : game.getRegisteredPlayers()) {
                    if (p.getName().equals(name1)) {
                        p1 = p;
                        break;
                    }
                }
                if (p1 != null) {
                    int countersHeld = 0;
                    for (Card c : p1.getCardsIn(ZoneType.Hand)) {
                        for (SpellAbility csa : c.getBasicSpells()) {
                            if (csa.getApi() == ApiType.Counter) {
                                countersHeld++;
                                break;
                            }
                        }
                    }
                    List<Card> oppCreatures = new ArrayList<Card>();
                    for (Player opp : game.getRegisteredPlayers()) {
                        if (opp.equals(p1)) {
                            continue;
                        }
                        for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
                            if (c.isCreature()) {
                                oppCreatures.add(c);
                            }
                        }
                    }
                    Collections.sort(oppCreatures, new Comparator<Card>() {
                        @Override
                        public int compare(Card a, Card b) {
                            return b.getNetPower() - a.getNetPower();
                        }
                    });
                    StringBuilder oppThreats = new StringBuilder();
                    for (int i = 0; i < Math.min(4, oppCreatures.size()); i++) {
                        if (i > 0) {
                            oppThreats.append(";");
                        }
                        oppThreats.append(oppCreatures.get(i).getName());
                    }
                    System.out.println(String.format("#GAMEEND\t%s\t%s\t%d\t%s\t%d\t%d\t%d\t%d\t%s",
                            name1, name2, gameNumber,
                            nonResult ? "T" : (winnerNum == 1 ? "W" : "L"),
                            turns, tracker.getOurTurns(), tracker.getOurLandDrops(), countersHeld,
                            oppThreats.length() > 0 ? oppThreats.toString() : "-"));
                    // Opponent commander casts with our answer-state snapshot at cast time:
                    // #CMDRCAST deck opp gameNum result seq name countersHeld creatureCapable untappedLands outcome
                    String gameResult = nonResult ? "T" : (winnerNum == 1 ? "W" : "L");
                    for (String row : tracker.getCommanderCastLog()) {
                        System.out.println("#CMDRCAST\t" + name1 + "\t" + name2 + "\t" + gameNumber
                                + "\t" + gameResult + "\t" + row);
                    }
                    // Counter-allocation: what each of our counters targeted.
                    // #COUNTERCAST deck opp gameNum result ourCounter targetName tgtIsCmdr tgtIsCreature tgtCMC commanderLoomable
                    for (String row : tracker.getCounterCastLog()) {
                        System.out.println("#COUNTERCAST\t" + name1 + "\t" + name2 + "\t" + gameNumber
                                + "\t" + gameResult + "\t" + row);
                    }
                }
            } catch (Exception e) {
                // The recorder must never break a sim.
            }
        }

        if (nonResult) {
            return new GameResult(gameNumber, 0, true, 0);
        }
        return new GameResult(gameNumber, winnerNum, false, turns);
    }
}
