package forge.ai.simulation;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Repro / regression guard for the "creature with a Reach keyword counter can't block Signal Pest" report.
 *
 * The bug was observed in MULTIPLAYER. A read-only investigation concluded the single-player code path is
 * correct: a Reach keyword counter registers a hidden {@code AddKeyword$ Reach} static (Card.createCounterStatic)
 * that surfaces through {@code hasStartOfUnHiddenKeyword("Reach")}, which is exactly what the block-legality
 * check ({@code StaticAbilityCantAttackBlock.applyCantBlockByAbility} for {@code withoutReach}) reads.
 *
 * This test pins that single-player behavior:
 *   - If it PASSES, the keyword-classification path is sound, so the multiplayer failure is a state-sync /
 *     continuous-effect-application timing issue that requires a live networked repro to fix (not a
 *     classification bug), and this test guards against a future regression of the single-player path.
 *   - If it FAILS, there is a real static-application bug to fix at its source.
 */
public class SignalPestReachCounterTest extends SimulationTest {

    @Test
    public void reachKeywordCounterAllowsBlockingSignalPest() {
        Game game = initAndCreateGame();
        Player attacker = game.getPlayers().get(0);
        Player defender = game.getPlayers().get(1);

        Card pest = addCard("Signal Pest", attacker);

        // Control: a vanilla creature (no flying/reach) may NOT block Signal Pest — proves the restriction is live.
        Card plainBlocker = addCard("Runeclaw Bear", defender);
        // Subject: the same creature, granted Reach by a keyword counter.
        Card reachBlocker = addCard("Runeclaw Bear", defender);
        reachBlocker.addCounterInternal(CounterType.getType("Reach"), 1, defender, false, null, null);

        game.getAction().checkStateEffects(true);

        AssertJUnit.assertTrue("Reach keyword counter should grant the unhidden Reach keyword",
                reachBlocker.hasStartOfUnHiddenKeyword("Reach"));
        AssertJUnit.assertFalse("Control: a creature without flying/reach must not be able to block Signal Pest",
                CombatUtil.canBlock(pest, plainBlocker));
        AssertJUnit.assertTrue("A creature with a Reach keyword counter must be able to block Signal Pest",
                CombatUtil.canBlock(pest, reachBlocker));
    }
}
