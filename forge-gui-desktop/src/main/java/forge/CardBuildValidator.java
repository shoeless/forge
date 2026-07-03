package forge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.item.IPaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * Headless card-build validator. For each card name in the input file, runs the full
 * CardFactory.getCard build path (via Card.fromPaperCard) and classifies:
 *   PLAYABLE     - builds cleanly with the current engine
 *   NEEDS_ENGINE - build threw (unknown ApiType/Trigger/Replacement/StaticMode) OR logged a
 *                  keyword-parse "crash" to stderr; the reason names the missing mechanic
 *   NOT_LOADED   - name not in the card DB (shouldn't happen after a cardsfolder sync)
 * Usage: java forge.CardBuildValidator <namesFile> <outCsv>
 */
public class CardBuildValidator {
    public static void main(String[] args) throws Exception {
        String namesFile = args.length > 0 ? args[0] : "tmp/new_card_names.txt";
        String outFile = args.length > 1 ? args[1] : "tmp/card_classification.csv";

        System.out.print("Initializing Forge engine... ");
        long t0 = System.currentTimeMillis();
        GuiBase.setInterface(new GuiDesktop());
        // Full load. CardStorageReader now skips (logs "[CARD-SKIP]") any card that fails to parse
        // instead of deadlocking the loader latch, so the DB loads fully minus the unparseable cards
        // (which are the most-severe NEEDS_ENGINE cases -> they show up as NOT_LOADED below).
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        System.out.println("done (" + (System.currentTimeMillis() - t0) / 1000.0 + "s)");

        // Throwaway game + player (mirrors AITest.resetGame) so ability construction has a real owner/game.
        List<RegisteredPlayer> players = new ArrayList<>();
        Deck d = new Deck();
        players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p1", null)));
        players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p2", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "CardValidate");
        Game game = new Game(players, rules, match);
        Player owner = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, owner);
        game.getPhaseHandler().onStackResolved();

        List<String> names = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(namesFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    names.add(line);
                }
            }
        }
        System.out.println("Testing " + names.size() + " cards...");

        final PrintStream realErr = System.err;
        int playable = 0, needsEngine = 0, notLoaded = 0, i = 0;
        try (PrintWriter csv = new PrintWriter(new FileWriter(outFile))) {
            csv.println("card,status,reason");
            for (String name : names) {
                if (++i % 200 == 0) {
                    System.out.println("  ... " + i + "/" + names.size());
                }
                IPaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
                if (pc == null) {
                    // Lazy parse on demand. A parse failure (our older reader can't handle a new
                    // card's script syntax) throws here -> NEEDS_ENGINE at the parse level.
                    System.setErr(new PrintStream(new ByteArrayOutputStream()));
                    try {
                        StaticData.instance().attemptToLoadCard(name);
                        System.setErr(realErr);
                    } catch (Throwable ex) {
                        System.setErr(realErr);
                        csv.println(esc(name) + ",NEEDS_ENGINE,"
                                + esc("PARSE " + ex.getClass().getSimpleName() + ": " + firstLine(ex.getMessage())));
                        needsEngine++;
                        continue;
                    } finally {
                        System.setErr(realErr);
                    }
                    pc = FModel.getMagicDb().getCommonCards().getCard(name);
                }
                if (pc == null) {
                    csv.println(esc(name) + ",NOT_LOADED,name not found in DB after lazy load");
                    notLoaded++;
                    continue;
                }

                ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
                String status;
                String reason = "";
                System.setErr(new PrintStream(errBuf));
                try {
                    Card.fromPaperCard(pc, owner);
                    System.setErr(realErr);
                    String errText = errBuf.toString();
                    String crash = failureLine(errText);
                    if (crash != null) {
                        status = "NEEDS_ENGINE";
                        reason = crash;
                    } else {
                        status = "PLAYABLE";
                    }
                } catch (Throwable ex) {
                    System.setErr(realErr);
                    status = "NEEDS_ENGINE";
                    reason = ex.getClass().getSimpleName() + ": " + firstLine(ex.getMessage());
                } finally {
                    System.setErr(realErr);
                }
                csv.println(esc(name) + "," + status + "," + esc(reason));
                if ("PLAYABLE".equals(status)) {
                    playable++;
                } else {
                    needsEngine++;
                }
            }
        }
        System.out.println("=== RESULT: PLAYABLE=" + playable + " NEEDS_ENGINE=" + needsEngine
                + " NOT_LOADED=" + notLoaded + " (of " + names.size() + ") ===");
        System.out.println("wrote " + outFile);
        System.exit(0);
    }

    // A keyword-family failure logs to stderr instead of throwing (Card.java "crash in Keyword parsing"),
    // and an unknown value logs "not found in ... enum". Return the offending line, or null if clean.
    private static String failureLine(String errText) {
        if (errText == null || errText.isEmpty()) {
            return null;
        }
        for (String l : errText.split("\n")) {
            if (l.contains("crash in Keyword") || l.contains("not found in") || l.contains("Unsupported")) {
                return l.trim();
            }
        }
        return null;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
        return s.contains(",") ? "\"" + s + "\"" : s;
    }
}
