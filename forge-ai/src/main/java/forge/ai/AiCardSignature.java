package forge.ai;

import java.util.HashSet;
import java.util.Set;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Computes a cheap, decision-relevant identity signature (a String key) for a {@link Card}, used by
 * the AI decision caches (ability picker + attack + block) so that multiple IDENTICAL cards — e.g. a
 * swarm of identical tokens — reuse a single evaluation instead of recomputing it per object.
 *
 * <p>The key captures every Card attribute the picker / attack / block decisions branch on. Two cards
 * share a key ONLY if every such attribute matches, so reusing one's cached decision for the other is
 * behavior-neutral within a single decision point (where the board snapshot is fixed). It returns
 * {@code null} for cards we cannot cheaply fingerprint in full (a transformable card mid-evaluation, a
 * merged pile); callers MUST treat null as "do not cache — compute normally".
 *
 * <p>The signature is intentionally far cheaper than the evaluations it guards (canDestroy* /
 * SpellAbilityFactors.calculate / canPlayAndPayFor each walk replacement handlers, combat triggers and
 * static abilities). When in doubt about a new decision-relevant attribute, ADD it here or return null
 * — never silently omit, or two genuinely different cards collapse and the AI misplays.
 */
public final class AiCardSignature {
    private AiCardSignature() { }

    // Counter types the picker/attack/block paths read: P1P1/M1M1 (Persist/Undying + combat math),
    // SHIELD/STUN (evaluator + can't-untap), TIME/FADE (upkeep/fading).
    private static final CounterEnumType[] RELEVANT_COUNTERS = {
        CounterEnumType.P1P1, CounterEnumType.M1M1, CounterEnumType.SHIELD,
        CounterEnumType.STUN, CounterEnumType.TIME, CounterEnumType.FADE
    };

    // SVar flags consulted by the combat / ability-evaluation paths.
    private static final String[] RELEVANT_SVARS = {
        "HasAttackEffect", "HasCombatEffect", "HasBlockEffect", "SacMe",
        "NonCombatPriority", "DestroyWhenDamaged", "AIEvaluationModifier", "EndOfTurnLeavePlay"
    };

    /**
     * @return a decision signature for {@code c}, or {@code null} if it must not be cached
     *         (transformable mid-evaluation, or a merged pile).
     */
    public static String of(final Card c) {
        if (c == null) {
            return null;
        }
        // A transformable card not currently in its alternate state can be evaluated against an
        // LKI/transformed copy whose result depends on the other face — too risky to fingerprint.
        if (c.isTransformable() && !c.isInAlternateState()) {
            return null;
        }
        // Merged piles are not interchangeable.
        if (c.hasMergedCard()) {
            return null;
        }

        final Player controller = c.getController();
        final StringBuilder sb = new StringBuilder(96);
        sb.append(c.getName());
        sb.append('|').append(controller == null ? -1 : controller.getId());
        sb.append('|').append(c.getType());                                       // folds type changes
        sb.append('|').append(c.getCurrentStateName());                           // DFC/MDFC face
        sb.append('|').append(c.getNetPower());
        sb.append('|').append(c.getNetToughness());
        sb.append('|').append(c.getNetCombatDamage());                            // toughness-assigns / P-T swap
        sb.append('|').append(c.getDamage());                                     // marked damage -> lethal/trade
        sb.append('|').append(c.getCMC());
        sb.append('|').append(c.isTapped() ? 1 : 0);
        sb.append('|').append(controller != null && c.canUntap(controller, true) ? 1 : 0);
        sb.append('|').append(c.hasSickness() ? 1 : 0);
        sb.append('|').append(c.isPhasedOut() ? 1 : 0);
        sb.append('|').append(c.isToken() ? 1 : 0);
        sb.append('|').append(c.isGoaded() ? 1 : 0);
        sb.append('|').append(c.isPaired() ? 1 : 0);
        sb.append('|').append(c.hasEncodedCard() ? 1 : 0);
        // every keyword incl. granted ones AND their magnitudes (getOriginal() carries "Annihilator 2")
        sb.append('|').append(c.getKeywordKey());
        // relevant counters
        sb.append('|');
        for (int i = 0; i < RELEVANT_COUNTERS.length; i++) {
            sb.append(c.getCounters(RELEVANT_COUNTERS[i])).append(',');
        }
        // attachments (auras/equipment): their stat/keyword grants are already in netP/T + keywordKey;
        // this catches non-stat grants (e.g. an aura adding an activated ability)
        sb.append('|');
        for (final Card att : c.getAttachedCards()) {
            sb.append(att.getName()).append(att.isPhasedOut() ? '#' : '.');
        }
        // SVar flags the decision paths consult
        sb.append('|');
        for (int i = 0; i < RELEVANT_SVARS.length; i++) {
            if (c.hasSVar(RELEVANT_SVARS[i])) {
                sb.append(RELEVANT_SVARS[i]).append('=').append(c.getSVar(RELEVANT_SVARS[i])).append(';');
            }
        }
        // catch-all: any continuous P/T / type / keyword / SVar layer applied bumps this
        sb.append('|').append(c.getTimestampTableSize());
        return sb.toString();
    }

    // ---- Gate helpers ----------------------------------------------------------------------------
    // The identity cache only pays off when the board actually has interchangeable duplicates. On an
    // all-unique board (the common case in singleton/Commander) it would be pure signature overhead,
    // so each cache site consults one of these cheap once-per-decision checks and skips allocation
    // (the wrappers' "if (cache != null)" guards then avoid computing any signatures).

    /** True if the battlefield has 2+ creatures sharing a name (where the combat caches can hit). */
    public static boolean boardHasDuplicateCreatures(final Game game) {
        final Set<String> seen = new HashSet<>();
        for (final Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && !seen.add(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** True if the battlefield has 2+ tokens sharing a name (where the picker decline-memo can hit). */
    public static boolean boardHasDuplicateTokens(final Game game) {
        final Set<String> seen = new HashSet<>();
        for (final Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.isToken() && !seen.add(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
