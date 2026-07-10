package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbilityValue;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardUtil;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

public class RemoveFromCombatAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision canPlay(Player aiPlayer, SpellAbility sa) {
        String logic = sa.getParam("AILogic");

        // ProtectValuableAttacker: Remove creatures with valuable attack triggers from combat
        // after triggers have fired but before combat damage. Used for Reconnaissance with Kaalia etc.
        if ("ProtectValuableAttacker".equals(logic)) {
            return checkProtectValuableAttacker(aiPlayer, sa);
        }

        // Default: disabled for the AI for now
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    /**
     * Check if we should protect a valuable attacking creature by removing it from combat.
     * This is used by Reconnaissance to protect creatures like Kaalia after their attack
     * triggers have already fired.
     */
    private AiAbilityDecision checkProtectValuableAttacker(Player aiPlayer, SpellAbility sa) {
        PhaseHandler ph = aiPlayer.getGame().getPhaseHandler();
        Combat combat = aiPlayer.getGame().getCombat();

        // Must be in combat and after declare blockers (triggers have fired)
        // but before combat damage (creature would die)
        if (combat == null || !ph.isPlayerTurn(aiPlayer)) {
            return new AiAbilityDecision(0, AiPlayDecision.TimingRestrictions);
        }

        PhaseType phase = ph.getPhase();
        // After declare blockers but before first strike damage or regular damage
        if (phase.isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            return new AiAbilityDecision(0, AiPlayDecision.TimingRestrictions);
        }
        if (phase.isAfter(PhaseType.COMBAT_DAMAGE)) {
            return new AiAbilityDecision(0, AiPlayDecision.TimingRestrictions);
        }

        // Get valid targets (attacking creatures we control)
        List<Card> targetable = CardUtil.getValidCardsToTarget(sa);
        if (targetable.isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        // Find the best creature to protect:
        // 1. Has valuable attack triggers
        // 2. Would die in combat (or is blocked and we want to preserve it)
        // 3. Is valuable enough to be worth protecting
        Card bestTarget = null;
        int bestValue = 0;

        for (Card attacker : targetable) {
            // Check if this creature has valuable attack triggers
            int attackTriggerValue = ComputerUtilAbilityValue.evaluateAttackTriggerValue(attacker);
            if (attackTriggerValue < 150) {
                continue; // Not valuable enough to protect
            }

            // Check if this creature is in danger
            boolean isBlocked = combat.isBlocked(attacker);
            boolean wouldDie = false;

            if (isBlocked) {
                // Estimate if the creature would die from blockers
                CardCollection blockers = combat.getBlockers(attacker);
                if (blockers != null && !blockers.isEmpty()) {
                    // Rough estimate: if total blocker power >= attacker toughness, it might die
                    int blockerPower = 0;
                    for (Card blocker : blockers) {
                        blockerPower += blocker.getNetPower();
                    }
                    wouldDie = blockerPower >= attacker.getNetToughness();
                }
            }

            // Even if not blocked, we might want to protect very valuable creatures
            // to allow them to attack again next turn (untap effect of Reconnaissance)
            int creatureValue = ComputerUtilCard.evaluateCreature(attacker);
            int totalValue = attackTriggerValue + (wouldDie ? creatureValue : creatureValue / 2);

            // Prioritize creatures that would actually die, but still consider
            // very valuable attackers even if they wouldn't die
            if (wouldDie && totalValue > bestValue) {
                bestValue = totalValue;
                bestTarget = attacker;
            } else if (bestTarget == null && attackTriggerValue >= 400) {
                // Very high value attack trigger (like Kaalia), protect even if not dying
                bestValue = totalValue;
                bestTarget = attacker;
            }
        }

        if (bestTarget != null) {
            sa.resetTargets();
            sa.getTargets().add(bestTarget);
            return new AiAbilityDecision(bestValue / 10, AiPlayDecision.WillPlay);
        }

        return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        if ("RemoveBestAttacker".equals(sa.getParam("AILogic"))) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        if ("ProtectValuableAttacker".equals(sa.getParam("AILogic"))) {
            return checkProtectValuableAttacker(aiPlayer, sa);
        }

        // TODO - implement AI
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#doTriggerAINoCost(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility, boolean)
     */
    @Override
    protected AiAbilityDecision doTriggerNoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if ("ProtectValuableAttacker".equals(sa.getParam("AILogic"))) {
            AiAbilityDecision decision = checkProtectValuableAttacker(aiPlayer, sa);
            if (decision.willingToPlay() || !mandatory) {
                return decision;
            }
        }

        if (mandatory) {
            // Must choose something
            List<Card> targetable = CardUtil.getValidCardsToTarget(sa);
            if (!targetable.isEmpty()) {
                sa.resetTargets();
                sa.getTargets().add(targetable.get(0));
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        }

        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }
}
