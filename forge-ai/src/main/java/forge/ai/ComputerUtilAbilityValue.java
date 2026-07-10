package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;

/**
 * Utility class for evaluating the value of spell abilities, triggered abilities,
 * and card abilities for AI decision making.
 *
 * Used by:
 * - Darksteel Mutation (target creatures with valuable abilities)
 * - Strionic Resonator (copy valuable triggered abilities)
 * - Reconnaissance (protect creatures with valuable attack triggers)
 */
public class ComputerUtilAbilityValue {

    // Base scores for different ApiTypes when evaluating abilities to copy/neutralize
    private static final int VALUE_CHANGE_ZONE_TO_BATTLEFIELD = 500;
    private static final int VALUE_ADD_PHASE = 400;
    private static final int VALUE_ADD_TURN = 600;
    private static final int VALUE_DRAW_PER_CARD = 200;
    private static final int VALUE_DEAL_DAMAGE_PER_POINT = 150;
    private static final int VALUE_DESTROY = 300;
    private static final int VALUE_DESTROY_ALL = 100;  // Low - rarely want to copy board wipes
    private static final int VALUE_TOKEN_PER_TOKEN = 150;
    private static final int VALUE_COUNTER = 300;
    private static final int VALUE_GAIN_CONTROL = 400;
    private static final int VALUE_MILL = 100;
    private static final int VALUE_GAIN_LIFE_PER_POINT = 50;
    private static final int VALUE_PUMP = 100;
    private static final int VALUE_PUT_COUNTER = 100;
    private static final int VALUE_DEFAULT = 50;

    // Modifiers for card characteristics
    private static final int MODIFIER_COMMANDER = 300;
    private static final int MODIFIER_TAP_COST = 50;
    private static final int MODIFIER_INDESTRUCTIBLE = 150;
    private static final int MODIFIER_HEXPROOF = 100;
    private static final int MODIFIER_SHROUD = 100;

    /**
     * Evaluates the total value of all abilities on a card.
     * Used for determining if a creature is a good target for ability removal (Darksteel Mutation).
     *
     * @param card the card to evaluate
     * @return total ability value score
     */
    public static int evaluateCardAbilities(Card card) {
        if (card == null) {
            return 0;
        }

        int totalValue = 0;

        // Evaluate activated abilities
        for (SpellAbility sa : card.getSpellAbilities()) {
            if (sa.isAbility() && !sa.isManaAbility()) {
                totalValue += evaluateSpellAbility(sa);
            }
        }

        // Evaluate triggered abilities
        for (Trigger trigger : card.getTriggers()) {
            SpellAbility triggerAbility = trigger.ensureAbility();
            if (triggerAbility != null) {
                int triggerValue = evaluateSpellAbility(triggerAbility);
                // Attack triggers are particularly valuable for aggressive strategies
                if (trigger.getMode() == TriggerType.Attacks ||
                    trigger.getMode() == TriggerType.AttackersDeclared) {
                    triggerValue = (triggerValue * 3) / 2;  // 50% bonus for attack triggers
                }
                totalValue += triggerValue;
            }
        }

        // Add modifiers based on card characteristics
        if (card.isCommander()) {
            totalValue += MODIFIER_COMMANDER;
        }
        if (card.hasKeyword(Keyword.INDESTRUCTIBLE)) {
            totalValue += MODIFIER_INDESTRUCTIBLE;
        }
        if (card.hasKeyword(Keyword.HEXPROOF)) {
            totalValue += MODIFIER_HEXPROOF;
        } else if (card.hasKeyword(Keyword.SHROUD)) {
            totalValue += MODIFIER_SHROUD;
        }

        return totalValue;
    }

    /**
     * Evaluates the value of a specific spell ability.
     * Used for determining if an ability is worth copying (Strionic Resonator).
     *
     * @param sa the spell ability to evaluate
     * @return ability value score
     */
    public static int evaluateSpellAbility(SpellAbility sa) {
        if (sa == null) {
            return 0;
        }

        // Handle wrapped abilities (triggered abilities on the stack)
        SpellAbility unwrapped = sa;
        if (sa.isWrapper()) {
            unwrapped = ((WrappedAbility) sa).getWrappedAbility();
        }

        ApiType api = unwrapped.getApi();
        if (api == null) {
            return VALUE_DEFAULT;
        }

        int value = getBaseValueForApiType(api, unwrapped);

        // Add modifier for repeatable abilities (tap cost = can use every turn)
        if (unwrapped.getPayCosts() != null && unwrapped.getPayCosts().hasTapCost()) {
            value += MODIFIER_TAP_COST;
        }

        return value;
    }

    /**
     * Gets the base value for an ApiType, with special handling for certain types
     * that need to look at ability parameters.
     */
    private static int getBaseValueForApiType(ApiType api, SpellAbility sa) {
        switch (api) {
            case ChangeZone:
                // Check if moving to battlefield (cheating creatures into play)
                String destination = sa.getParamOrDefault("Destination", "");
                if ("Battlefield".equals(destination)) {
                    return VALUE_CHANGE_ZONE_TO_BATTLEFIELD;
                }
                // Moving to hand/library/graveyard is less valuable
                return VALUE_DEFAULT;

            case AddPhase:
                return VALUE_ADD_PHASE;

            case AddTurn:
                return VALUE_ADD_TURN;

            case Draw:
                // Try to determine number of cards drawn
                int numCards = getNumericParam(sa, "NumCards", 1);
                return VALUE_DRAW_PER_CARD * numCards;

            case DealDamage:
                // Try to determine damage amount
                int damage = getNumericParam(sa, "NumDmg", 1);
                return VALUE_DEAL_DAMAGE_PER_POINT * Math.min(damage, 5);  // Cap at 5 to avoid overflow

            case Destroy:
                return VALUE_DESTROY;

            case DestroyAll:
                return VALUE_DESTROY_ALL;

            case Token:
                // Try to determine number of tokens
                int numTokens = getNumericParam(sa, "TokenAmount", 1);
                return VALUE_TOKEN_PER_TOKEN * numTokens;

            case Counter:
                return VALUE_COUNTER;

            case GainControl:
            case GainControlVariant:
                return VALUE_GAIN_CONTROL;

            case Mill:
                return VALUE_MILL;

            case GainLife:
                // Try to determine life gained
                int life = getNumericParam(sa, "LifeAmount", 1);
                return VALUE_GAIN_LIFE_PER_POINT * Math.min(life, 10);  // Cap at 10

            case Pump:
            case PumpAll:
                return VALUE_PUMP;

            case PutCounter:
            case PutCounterAll:
                return VALUE_PUT_COUNTER;

            case CopyPermanent:
                return VALUE_CHANGE_ZONE_TO_BATTLEFIELD;  // Copying is like creating

            default:
                return VALUE_DEFAULT;
        }
    }

    /**
     * Helper to safely get a numeric parameter from a SpellAbility.
     */
    private static int getNumericParam(SpellAbility sa, String param, int defaultValue) {
        if (!sa.hasParam(param)) {
            return defaultValue;
        }
        String paramValue = sa.getParam(param);
        try {
            return Integer.parseInt(paramValue);
        } catch (NumberFormatException e) {
            // Could be a variable like "X" - use default
            return defaultValue;
        }
    }

    /**
     * Evaluates the value of a creature's attack triggers.
     * Used for Reconnaissance to determine if a creature is worth protecting.
     *
     * @param card the attacking creature
     * @return total value of attack triggers
     */
    public static int evaluateAttackTriggerValue(Card card) {
        if (card == null) {
            return 0;
        }

        int totalValue = 0;

        for (Trigger trigger : card.getTriggers()) {
            TriggerType mode = trigger.getMode();

            // Only evaluate attack-related triggers
            if (mode != TriggerType.Attacks &&
                mode != TriggerType.AttackersDeclared &&
                mode != TriggerType.AttackerUnblocked &&
                mode != TriggerType.AttackerBlocked) {
                continue;
            }

            SpellAbility triggerAbility = trigger.ensureAbility();
            if (triggerAbility != null) {
                totalValue += evaluateSpellAbility(triggerAbility);
            }
        }

        // Commander creatures with attack triggers are especially valuable
        if (card.isCommander() && totalValue > 0) {
            totalValue += MODIFIER_COMMANDER;
        }

        return totalValue;
    }
}
