package forge;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
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
import forge.game.player.RegisteredPlayer;
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
                                cmdFormat, timeout, maxTurns);
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
    }

    /**
     * Runs a single game and returns the result. Designed to be called from any thread.
     */
    private static GameResult runSingleGame(int gameNumber, File deck1File, File deck2File,
            String name1, String name2, boolean isCommander, int timeoutSec, int maxTurns) {
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

        // Set up timeout timer
        final Game gameRef = game;
        Timer timer = new Timer(true);
        final boolean[] timedOut = {false};
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!gameRef.isGameOver()) {
                    timedOut[0] = true;
                    gameRef.setAge(GameStage.GameOver);
                }
            }
        }, timeoutSec * 1000L);

        // Set up turn limit monitor
        final int maxTurnLimit = maxTurns;
        final boolean[] turnLimitHit = {false};
        Thread turnMonitor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!gameRef.isGameOver()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (gameRef.getPhaseHandler() != null
                                && gameRef.getPhaseHandler().getTurn() > maxTurnLimit) {
                            turnLimitHit[0] = true;
                            gameRef.setAge(GameStage.GameOver);
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
            return new GameResult(gameNumber, 0, false, 0);
        }

        timer.cancel();

        // Read outcome
        GameOutcome outcome = game.getOutcome();
        if (timedOut[0] || turnLimitHit[0]) {
            return new GameResult(gameNumber, 0, true, 0);
        } else if (outcome == null || outcome.isDraw()) {
            return new GameResult(gameNumber, 0, false, 0);
        } else {
            RegisteredPlayer winner = outcome.getWinningPlayer();
            int winnerNum = 0;
            if (winner != null && winner.getPlayer().getName().equals(name1)) {
                winnerNum = 1;
            } else if (winner != null) {
                winnerNum = 2;
            }
            int turns = outcome.getLastTurnNumber() > 0 ? outcome.getLastTurnNumber() : 0;
            return new GameResult(gameNumber, winnerNum, false, turns);
        }
    }
}
