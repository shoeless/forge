package forge.ai.simulation;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityFlipCoinMod;
import forge.game.staticability.StaticAbilityMustAttack;
import forge.util.maps.MapToAmount;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Functional tests for the 4 StaticAbilityModes backported from upstream
 * (UntapOtherPlayer, CountersRemain, FlipCoinDoubler, AttackRequirement).
 * Proves the modes actually WORK at runtime, not just that the cards build.
 */
public class StaticModePortTest extends SimulationTest {

    @Test
    public void testUntapOtherPlayer_SeedbornMuse() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);

        addCard("Seedborn Muse", p0);
        Card forest = addCard("Forest", p0);
        forest.setTapped(true);
        AssertJUnit.assertTrue(forest.isTapped());

        // run p1's untap step; Seedborn Muse (p0's) should untap p0's permanents too
        game.getPhaseHandler().devModeSet(PhaseType.UNTAP, p1);
        game.getUntap().executeAt();

        AssertJUnit.assertFalse("Seedborn Muse must untap its controller's permanents during another player's untap step",
                forest.isTapped());
    }

    @Test
    public void testUntapOtherPlayer_offWithoutMuse() {
        // control: without Seedborn Muse, p0's tapped permanent stays tapped on p1's untap step
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);

        Card forest = addCard("Forest", p0);
        forest.setTapped(true);

        game.getPhaseHandler().devModeSet(PhaseType.UNTAP, p1);
        game.getUntap().executeAt();

        AssertJUnit.assertTrue("Without an untap-other-player effect, p0's permanent must stay tapped on p1's turn",
                forest.isTapped());
    }

    @Test
    public void testCountersRemain_Skullbriar() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);

        Card skull = addCard("Skullbriar, the Walking Grave", p0);
        skull.setCounters(CounterEnumType.P1P1, 3);
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertEquals(3, skull.getCounters(CounterEnumType.P1P1));

        // move to graveyard; Skullbriar's CountersRemain static must preserve its counters
        Card moved = game.getAction().moveToGraveyard(skull, null);
        AssertJUnit.assertEquals("Skullbriar must keep its +1/+1 counters as it moves to the graveyard",
                3, moved.getCounters(CounterEnumType.P1P1));
    }

    @Test
    public void testCountersRemain_offForNormalCreature() {
        // control: a normal creature loses its counters on moving to graveyard
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);

        Card bear = addCard("Runeclaw Bear", p0);
        bear.setCounters(CounterEnumType.P1P1, 2);
        game.getAction().checkStaticAbilities();

        Card moved = game.getAction().moveToGraveyard(bear, null);
        AssertJUnit.assertEquals("A normal creature must lose its counters in the graveyard",
                0, moved.getCounters(CounterEnumType.P1P1));
    }

    @Test
    public void testFlipCoinDoubler_KrarksThumb() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);

        // no doubler yet -> multiplier 1
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertEquals(1, StaticAbilityFlipCoinMod.getFlipMultiplier(p0));

        addCard("Krark's Thumb", p0);
        game.getAction().checkStaticAbilities();
        AssertJUnit.assertEquals("Krark's Thumb must double the flip multiplier",
                2, StaticAbilityFlipCoinMod.getFlipMultiplier(p0));
    }

    @Test
    public void testAttackRequirement_ViashinoBey() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);

        Card bey = addCard("Viashino Bey", p0);
        Card bear = addCard("Runeclaw Bear", p0);
        game.getAction().checkStaticAbilities();

        // "If Viashino Bey attacks, all creatures you control attack if able."
        MapToAmount<Card> forced = StaticAbilityMustAttack.getAttackRequirements(bey, new CardCollection(bear));
        AssertJUnit.assertTrue("Viashino Bey's AttackRequirement must force other creatures you control to attack",
                forced.count(bear) > 0);

        // control: an unrelated card triggers no forced attackers
        MapToAmount<Card> none = StaticAbilityMustAttack.getAttackRequirements(bear, new CardCollection(bey));
        AssertJUnit.assertEquals("Runeclaw Bear has no AttackRequirement", 0, none.count(bey));
    }
}
