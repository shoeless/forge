package forge.ai.ability;

import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import forge.ai.*;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CounterEffect;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardPredicates;
import forge.game.cost.Cost;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostSacrifice;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.collect.FCollectionView;

public class CounterAi extends SpellAbilityAi {

    @Override
    protected AiAbilityDecision checkApiLogic(Player ai, SpellAbility sa) {
        boolean toReturn = true;
        final Card source = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        final Game game = ai.getGame();
        int tgtCMC = 0;
        SpellAbility tgtSA = null;

        if (game.getStack().isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        if ("Force of Will".equals(sourceName)) {
            if (!SpecialCardAi.ForceOfWill.consider(ai, sa)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }
        }

        if (sa.usesTargeting()) {
            final SpellAbility topSA = ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);
            if ((topSA.isSpell() && !topSA.isCounterableBy(sa)) || ai.getYourTeam().contains(topSA.getActivatingPlayer())) {
                // might as well check for player's friendliness
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            } else if (sa.hasParam("ConditionWouldDestroy") && !CounterEffect.checkForConditionWouldDestroy(sa, topSA)) {
                return new AiAbilityDecision(0, AiPlayDecision.CantPlaySa);
            }

            // check if the top ability on the stack corresponds to the AI-specific targeting declaration, if provided
            if (sa.hasParam("AITgts") && (topSA.getHostCard() == null
                    || !topSA.getHostCard().isValid(sa.getParam("AITgts"), sa.getActivatingPlayer(), source, sa))) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            if (sa.hasParam("UnlessCost") && "TargetedController".equals(sa.getParamOrDefault("UnlessPayer", "TargetedController"))) {
                Cost unlessCost = AbilityUtils.calculateUnlessCost(sa, sa.getParam("UnlessCost"), false);
                if (unlessCost.hasSpecificCostType(CostDiscard.class)) {
                    CostDiscard discardCost = unlessCost.getCostPartByType(CostDiscard.class);
                    if ("Hand".equals(discardCost.getType())) {
                        if (topSA.getActivatingPlayer().getCardsIn(ZoneType.Hand).size() < 2) {
                            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                        }
                    }
                }
                // TODO check if Player can pay the unless cost?
            }

            sa.resetTargets();
            if (sa.canTargetSpellAbility(topSA)) {
                sa.getTargets().add(topSA);
                if (topSA.getPayCosts().getTotalMana() != null) {
                    tgtSA = topSA;
                    tgtCMC = topSA.getPayCosts().getTotalMana().getCMC();
                    tgtCMC += topSA.getPayCosts().getTotalMana().countX() > 0 ? 3 : 0; // TODO: somehow determine the value of X paid and account for it?
                }
            } else {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
        } else {
            // This spell doesn't target. Must be a "Counter All" or "Counter trigger" type of ability.
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        String unlessCost = sa.hasParam("UnlessCost") ? sa.getParam("UnlessCost").trim() : null;

        if (unlessCost != null && !unlessCost.endsWith(">")) {
            Player opp = tgtSA.getActivatingPlayer();
            int usableManaSources = ComputerUtilMana.getAvailableManaEstimate(opp);

            int toPay = 0;
            boolean setPayX = false;
            if (unlessCost.equals("X") && sa.getSVar(unlessCost).equals("Count$xPaid")) {
                setPayX = true;
                toPay = Math.min(ComputerUtilCost.getMaxXValue(sa, ai, true), usableManaSources + 1);
            } else {
                toPay = AbilityUtils.calculateAmount(source, unlessCost, sa);
            }

            if (toPay == 0) {
                return new AiAbilityDecision(0, AiPlayDecision.CantAffordX);
            }

            if (toPay <= usableManaSources) {
                // If this is a reusable Resource, feel free to play it most of the time
                if (!playReusable(ai, sa)) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantAfford);
                }
            }

            if (setPayX) {
                sa.setXManaCostPaid(toPay);
            }
        }

        // TODO Improve AI

        // Will return true if this spell can counter (or is Reusable and can
        // force the Human into making decisions)

        // But really it should be more picky about how it counters things

        if (sa.hasParam("AILogic")) {
            String logic = sa.getParam("AILogic");
            if (logic.startsWith("MinCMC.")) { // TODO fix Daze and fold into AITgts
                int minCMC = Integer.parseInt(logic.substring(7));
                if (tgtCMC < minCMC) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            } else if ("NullBrooch".equals(logic)) {
                if (!SpecialCardAi.NullBrooch.consider(ai, sa)) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            } else if ("NoCommander".equals(logic)) {
                // Bounce-counters (Remand, Memory Lapse) shouldn't target commanders
                // since the spell goes to hand/library — no command tax increase,
                // opponent just recasts it next turn
                if (tgtSA != null && tgtSA.getHostCard() != null && tgtSA.getHostCard().isCommander()) {
                    return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
                }
            }
        }

        // Specific constraints for the AI to use/not use counterspells against specific groups of spells
        boolean ctrCmc0ManaPerms = AiProfileUtil.getBoolProperty(ai, AiProps.ALWAYS_COUNTER_CMC_0_MANA_MAKING_PERMS);
        boolean ctrDamageSpells = AiProfileUtil.getBoolProperty(ai, AiProps.ALWAYS_COUNTER_DAMAGE_SPELLS);
        boolean ctrRemovalSpells = AiProfileUtil.getBoolProperty(ai, AiProps.ALWAYS_COUNTER_REMOVAL_SPELLS);
        boolean ctrPumpSpells = AiProfileUtil.getBoolProperty(ai, AiProps.ALWAYS_COUNTER_PUMP_SPELLS);
        boolean ctrAuraSpells = AiProfileUtil.getBoolProperty(ai, AiProps.ALWAYS_COUNTER_AURAS);
        boolean ctrOtherCounters = AiProfileUtil.getBoolProperty(ai, AiProps.ALWAYS_COUNTER_OTHER_COUNTERSPELLS);
        int ctrChanceCMC1 = AiProfileUtil.getIntProperty(ai, AiProps.CHANCE_TO_COUNTER_CMC_1);
        int ctrChanceCMC2 = AiProfileUtil.getIntProperty(ai, AiProps.CHANCE_TO_COUNTER_CMC_2);
        int ctrChanceCMC3 = AiProfileUtil.getIntProperty(ai, AiProps.CHANCE_TO_COUNTER_CMC_3);
        String ctrNamed = AiProfileUtil.getProperty(ai, AiProps.ALWAYS_COUNTER_SPELLS_FROM_NAMED_CARDS);
        boolean dontCounter = false;

        if (tgtCMC == 1 && !MyRandom.percentTrue(ctrChanceCMC1)) {
            dontCounter = true;
        } else if (tgtCMC == 2 && !MyRandom.percentTrue(ctrChanceCMC2)) {
            dontCounter = true;
        } else if (tgtCMC == 3 && !MyRandom.percentTrue(ctrChanceCMC3)) {
            dontCounter = true;
        }

        // Always counter commander casts — they are the highest priority target
        if (tgtSA != null && tgtSA.getHostCard() != null && tgtSA.getHostCard().isCommander()) {
            dontCounter = false;
        }

        // When counters are scarce (3 or fewer in hand), only counter
        // high-priority targets. Save counters for what matters.
        // Skip this check for bounce-counters (Destination$ Hand like Remand)
        // since they're lower-commitment tempo plays acceptable on weaker targets.
        if (tgtSA != null && !dontCounter
                && (tgtSA.getHostCard() == null || !tgtSA.getHostCard().isCommander())
                && !sa.hasParam("Destination")) {
            int countersInHand = 0;
            for (Card c : ai.getCardsIn(ZoneType.Hand)) {
                for (SpellAbility ability : c.getNonManaAbilities()) {
                    if (ability.getApi() == ApiType.Counter) {
                        countersInHand++;
                        break;
                    }
                }
            }
            if (countersInHand <= 3) {
                Card tgtCard = tgtSA.getHostCard();
                boolean isHighPriority = false;
                if (tgtCard != null) {
                    // Enchantments are high priority (persistent value, strategy multipliers)
                    isHighPriority |= tgtCard.isEnchantment();
                    // CMC 4+ creatures are real threats
                    isHighPriority |= tgtCard.isCreature() && tgtCMC >= 4;
                    // Planeswalkers
                    isHighPriority |= tgtCard.isPlaneswalker();
                    // Pump spells when opponent has a threatening creature
                    isHighPriority |= (tgtSA.getApi() == ApiType.Pump || tgtSA.getApi() == ApiType.PumpAll);
                    // Damage/removal targeting the AI
                    isHighPriority |= tgtSA.getApi() == ApiType.DealDamage || tgtSA.getApi() == ApiType.Destroy;
                    // Other counterspells
                    isHighPriority |= tgtSA.getApi() == ApiType.Counter;
                }
                if (!isHighPriority) {
                    dontCounter = true;
                }
            }
        }

        if (tgtSA != null && tgtCMC < AiProfileUtil.getIntProperty(ai, AiProps.MIN_SPELL_CMC_TO_COUNTER)) {
            dontCounter = true;
            Card tgtSource = tgtSA.getHostCard();
            if ((tgtSource != null && tgtCMC == 0 && tgtSource.isPermanent() && !tgtSource.getManaAbilities().isEmpty() && ctrCmc0ManaPerms)
                    || (tgtSA.getApi() == ApiType.DealDamage || tgtSA.getApi() == ApiType.LoseLife || tgtSA.getApi() == ApiType.DamageAll && ctrDamageSpells)
                    || (tgtSA.getApi() == ApiType.Counter && ctrOtherCounters)
                    || ((tgtSA.getApi() == ApiType.Pump || tgtSA.getApi() == ApiType.PumpAll) && ctrPumpSpells)
                    || (tgtSA.getApi() == ApiType.Attach && ctrAuraSpells)
                    || (tgtSA.getApi() == ApiType.Destroy || tgtSA.getApi() == ApiType.DestroyAll || tgtSA.getApi() == ApiType.Sacrifice
                       || tgtSA.getApi() == ApiType.SacrificeAll && ctrRemovalSpells)) {
                dontCounter = false;
            }

            if (tgtSource != null && !ctrNamed.isEmpty() && !"none".equalsIgnoreCase(ctrNamed)) {
                for (String name : StringUtils.split(ctrNamed, ";")) {
                    if (name.equals(tgtSource.getName())) {
                        dontCounter = false;
                    }
                }
            }

            // should not refrain from countering a CMC X spell if that's the only CMC
            // counterable with that particular counterspell type (e.g. Mental Misstep vs. CMC 1 spells)
            if (sa.getParamOrDefault("ValidTgts", "").startsWith("Card.cmcEQ")) {
                int validTgtCMC = AbilityUtils.calculateAmount(source, sa.getParam("ValidTgts").substring(10), sa);
                if (tgtCMC == validTgtCMC) {
                    dontCounter = false;
                }
            }
        }

        // Should ALWAYS counter if it doesn't spend a card, otherwise it wastes an opportunity
        // to gain card advantage
        if (sa.isAbility()
                && (!sa.getPayCosts().hasSpecificCostType(CostDiscard.class))
                && (!sa.getPayCosts().hasSpecificCostType(CostSacrifice.class))
                && (!sa.getPayCosts().hasSpecificCostType(CostExile.class))) {
            // TODO: maybe also disallow CostPayLife?
            dontCounter = false;
        }

        // Null Brooch is special - it has a discard cost, but the AI will be
        // discarding no cards, or is playing a deck where discarding is a benefit
        // as defined in SpecialCardAi.NullBrooch
        if (sa.hasParam("AILogic")) {
            if ("NullBrooch".equals(sa.getParam("AILogic"))) {
                dontCounter = false;
            }
        }

        if (dontCounter) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(Player aiPlayer, SpellAbility sa) {
        return doTriggerNoCost(aiPlayer, sa, true);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        final Game game = ai.getGame();

        if (sa.usesTargeting()) {
            if (game.getStack().isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }

            sa.resetTargets();
            if (mandatory && !sa.canAddMoreTarget()) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
            Pair<SpellAbility, Boolean> pair = chooseTargetSpellAbility(game, sa, ai, mandatory);
            SpellAbility tgtSA = pair.getLeft();

            if (tgtSA == null) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
            sa.getTargets().add(tgtSA);
            if (!mandatory && !pair.getRight()) {
                // If not mandatory and not preferred, bail out after setting target
                return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
            }

            String unlessCost = sa.hasParam("UnlessCost") ? sa.getParam("UnlessCost").trim() : null;

            final Card source = sa.getHostCard();
            if (unlessCost != null) {
                Player opp = tgtSA.getActivatingPlayer();
                int usableManaSources = ComputerUtilMana.getAvailableManaEstimate(opp);

                int toPay = 0;
                boolean setPayX = false;
                if (unlessCost.equals("X") && sa.getSVar(unlessCost).equals("Count$xPaid")) {
                    setPayX = true;
                    toPay = ComputerUtilCost.getMaxXValue(sa, ai, true);
                } else {
                    toPay = AbilityUtils.calculateAmount(source, unlessCost, sa);
                }

                if (!mandatory) {
                    if (toPay == 0) {
                        return new AiAbilityDecision(0, AiPlayDecision.CantAffordX);
                    }

                    if (toPay <= usableManaSources) {
                        // If this is a reusable Resource, feel free to play it most of the time
                        if (!playReusable(ai,sa) || (MyRandom.getRandom().nextFloat() < .4)) {
                            return new AiAbilityDecision(0, AiPlayDecision.CantAfford);
                        }
                    }
                }

                if (setPayX) {
                    sa.setXManaCostPaid(toPay);
                }
            }
        }

        // TODO Improve AI

        // Will return true if this spell can counter (or is Reusable and can
        // force the Human into making decisions)

        // But really it should be more picky about how it counters things
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    public Pair<SpellAbility, Boolean> chooseTargetSpellAbility(Game game, SpellAbility sa, Player ai, boolean mandatory) {
        SpellAbility tgtSA;
        SpellAbility leastBadOption = null;
        SpellAbility bestOption = null;

        Iterator<SpellAbilityStackInstance> it = game.getStack().iterator();
        SpellAbilityStackInstance si = null;
        while (it.hasNext()) {
            si = it.next();
            tgtSA = si.getSpellAbility();
            if (!sa.canTargetSpellAbility(tgtSA)) {
                continue;
            }
            if (leastBadOption == null) {
                leastBadOption = tgtSA;
            }

            if ((tgtSA.isSpell() && !tgtSA.isCounterableBy(sa)) ||
                tgtSA.getActivatingPlayer() == ai ||
                !tgtSA.getActivatingPlayer().isOpponentOf(ai)) {
                // Is this a "better" least bad option
                if (leastBadOption.getActivatingPlayer().isOpponentOf(ai)) {
                    // NOOP
                } else if (sa.getActivatingPlayer().isOpponentOf(ai)) {
                    // Target opponents uncounterable stuff, before our own stuff
                    leastBadOption = tgtSA;
                }
                continue;
            }

            if (bestOption == null) {
                bestOption = tgtSA;
            } else {
                // Prefer countering commander casts — they are high-impact
                // threats that the entire opponent deck is built around
                boolean betterThanBest = false;
                Card tgtCard = tgtSA.getHostCard();
                Card bestCard = bestOption.getHostCard();
                boolean tgtIsCommander = tgtCard != null && tgtCard.isCommander();
                boolean bestIsCommander = bestCard != null && bestCard.isCommander();
                if (tgtIsCommander && !bestIsCommander) {
                    betterThanBest = true;
                } else if (!tgtIsCommander && !bestIsCommander) {
                    // Neither is a commander — prefer higher CMC
                    int tgtCmc = tgtSA.getPayCosts().getTotalMana() != null
                            ? tgtSA.getPayCosts().getTotalMana().getCMC() : 0;
                    int bestCmc = bestOption.getPayCosts().getTotalMana() != null
                            ? bestOption.getPayCosts().getTotalMana().getCMC() : 0;
                    betterThanBest = tgtCmc > bestCmc;
                }
                if (betterThanBest) {
                    bestOption = tgtSA;
                }
            }
        }

        return new ImmutablePair<>(bestOption != null ? bestOption : leastBadOption, bestOption != null);
    }

    @Override
    public boolean willPayUnlessCost(Player payer, SpellAbility sa, Cost cost, boolean alreadyPaid, FCollectionView<Player> payers) {
        final Card source = sa.getHostCard();
        final Game game = source.getGame();
        List<SpellAbility> spells = AbilityUtils.getDefinedSpellAbilities(source, sa.getParamOrDefault("Defined", "Targeted"), sa);
        for (SpellAbility toBeCountered : spells) {
            // ward or human misplay
            if (!toBeCountered.isCounterableBy(sa)) {
                return false;
            }

            if (toBeCountered.isSpell()) {
                Card spellHost = toBeCountered.getHostCard();
                Card gameCard = game.getCardState(spellHost, null);
                // Spell Host already left the Stack Zone
                if (gameCard == null || !gameCard.isInZone(ZoneType.Stack) || !gameCard.equalsWithGameTimestamp(spellHost)) {
                    return false;
                }
            }

            // no reason to pay if we don't plan to confirm
            if (toBeCountered.isOptionalTrigger() && !SpellApiToAi.Converter.get(toBeCountered).doTriggerNoCostWithSubs(payer, toBeCountered, false).willingToPlay()) {
                return false;
            }
            // TODO check hasFizzled
        }
        CardCollectionView hand = payer.getCardsIn(ZoneType.Hand);
        if (cost.hasSpecificCostType(CostDiscard.class)) {
            CostDiscard discard = cost.getCostPartByType(CostDiscard.class);
            String type = discard.getType();
            if (type.equals("Hand")) {
                if (hand.isEmpty()) {
                    return true;
                }

                // TODO how to check if the Spell on the Stack is more valuable than the Cards in Hand?
                int spellSum = 0;
                for (SpellAbility spell : spells) {
                    Card c = spell.getHostCard();
                    if (CardPredicates.CREATURES.test(c)) {
                        spellSum += ComputerUtilCard.evaluateCreature(c);
                    }
                }
                int handSum = 0;
                for (Card c : hand) {
                    if (CardPredicates.CREATURES.test(c)) {
                        handSum += ComputerUtilCard.evaluateCreature(c);
                    }
                }
                if (spellSum <= handSum) {
                    return false;
                }
            }
        }

        return super.willPayUnlessCost(payer, sa, cost, alreadyPaid, payers);
    }
}
