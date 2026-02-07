package forge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

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
 * Usage: java -cp <classpath> forge.DeckBattler <deck1.dck> <deck2.dck> [numGames] [timeoutSec]
 */
public class DeckBattler {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: forge.DeckBattler <deck1.dck> <deck2.dck> [numGames] [timeoutSec]");
            System.out.println("  numGames:   Number of games to play (default: 100)");
            System.out.println("  timeoutSec: Per-game timeout in seconds (default: 120)");
            System.exit(1);
        }

        File deck1File = new File(args[0]);
        File deck2File = new File(args[1]);
        int numGames = args.length >= 3 ? Integer.parseInt(args[2]) : 100;
        int timeoutSec = args.length >= 4 ? Integer.parseInt(args[3]) : 120;

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

        String name1 = deck1.getName();
        String name2 = deck2.getName();
        int cards1 = deck1.getMain().countAll();
        int cards2 = deck2.getMain().countAll();

        String format = isCommander ? "Commander" : "Constructed";
        System.out.println("Format: " + format);
        System.out.println("Deck 1: " + name1 + " (" + cards1 + " cards)");
        System.out.println("Deck 2: " + name2 + " (" + cards2 + " cards)");
        System.out.println("Running " + numGames + " games (timeout: " + timeoutSec + "s each)...");

        // Result tracking
        int wins1 = 0;
        int wins2 = 0;
        int draws = 0;
        int timeouts = 0;
        int totalTurns = 0;
        int completedGames = 0;

        int maxTurns = 200;

        for (int i = 1; i <= numGames; i++) {
            // Create fresh players and match for each game
            List<RegisteredPlayer> players = new ArrayList<RegisteredPlayer>();
            // Re-load decks each game to get fresh copies
            Deck d1 = DeckSerializer.fromFile(deck1File);
            Deck d2 = DeckSerializer.fromFile(deck2File);
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

            // Set up turn limit via game event monitoring
            // We'll check turns in a separate daemon thread
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
                System.err.println("  Game " + i + " error: " + e.getMessage());
                timer.cancel();
                draws++;
                completedGames++;
                if (i % 10 == 0 || i == numGames) {
                    System.out.println("  [" + i + "/" + numGames + "] "
                            + name1 + ": " + wins1 + "  " + name2 + ": " + wins2);
                }
                continue;
            }

            timer.cancel();
            completedGames++;

            // Read outcome
            GameOutcome outcome = game.getOutcome();
            if (timedOut[0] || turnLimitHit[0]) {
                timeouts++;
                draws++;
            } else if (outcome == null || outcome.isDraw()) {
                draws++;
            } else {
                RegisteredPlayer winner = outcome.getWinningPlayer();
                if (winner != null && winner.getPlayer().getName().equals(name1)) {
                    wins1++;
                } else if (winner != null) {
                    wins2++;
                } else {
                    draws++;
                }
                if (outcome.getLastTurnNumber() > 0) {
                    totalTurns += outcome.getLastTurnNumber();
                }
            }

            // Progress output every 10 games
            if (i % 10 == 0 || i == numGames) {
                System.out.println("  [" + i + "/" + numGames + "] "
                        + name1 + ": " + wins1 + "  " + name2 + ": " + wins2);
            }
        }

        // Print summary
        System.out.println();
        System.out.println("=== Results: " + completedGames + " games ===");

        int decidedGames = wins1 + wins2;
        double winRate1 = decidedGames > 0 ? (100.0 * wins1 / decidedGames) : 0;

        System.out.println("  " + name1 + " wins: " + wins1
                + " (" + String.format("%.1f", 100.0 * wins1 / completedGames) + "%)");
        System.out.println("  " + name2 + " wins: " + wins2
                + " (" + String.format("%.1f", 100.0 * wins2 / completedGames) + "%)");
        System.out.println("  Draws: " + draws + "   Timeouts: " + timeouts);

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

            System.out.println("  Win rate: " + String.format("%.1f", winRate1)
                    + "% [95% CI: " + String.format("%.1f", lower)
                    + "% - " + String.format("%.1f", upper) + "%]");
        }

        if (decidedGames > 0 && totalTurns > 0) {
            System.out.println("  Average turns: "
                    + String.format("%.1f", (double) totalTurns / decidedGames));
        }
    }
}
