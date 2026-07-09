package forge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEntityView;
import forge.ai.ComputerUtilCard;
import forge.card.CardStateName;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CardView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.event.GameEvent;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.StackItemView;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

/**
 * Per-game event listener that tracks card performance metrics for one player.
 * Subscribes to game events via Guava EventBus to attribute damage, mana production,
 * blocking value, and buff contributions to individual cards.
 */
public class CardPerformanceTracker {

    /** Per-card-name stats within a single game. */
    static class CardStats {
        double damageDealt;
        double manaCredit;
        double blockingDamage;
        double buffCredit;
        double removalValue;
        boolean played; // true if this card was cast or played (lands) this game

        double score() {
            return damageDealt + manaCredit + blockingDamage * 0.8 + buffCredit + removalValue * 0.8;
        }
    }

    private final Player trackedPlayer;
    private final Map<String, CardStats> cardStats = new HashMap<String, CardStats>();

    // Maps card name (the cast spell) -> set of mana source card names that paid for it
    private final Map<String, Set<String>> castManaSourcesMap = new HashMap<String, Set<String>>();

    // Maps opponent creature ID -> our spell's card name that targeted it (for removal attribution)
    private final Map<Integer, String> pendingRemovalTargets = new HashMap<Integer, String>();
    // Counter attribution: countered-creature-card-id -> our counterspell name / credited value
    private final Map<Integer, String> pendingCounterTargets = new HashMap<Integer, String>();
    private final Map<Integer, Integer> pendingCounterValues = new HashMap<Integer, Integer>();

    // Quest counter tracking: source card name -> total quest counters placed
    private final Map<String, Integer> questCounterSources = new HashMap<String, Integer>();
    private int totalQuestCountersPlaced = 0;

    // Energy counter tracking: source card name -> total energy produced
    private final Map<String, Integer> energyProducers = new HashMap<String, Integer>();
    private int totalEnergyProduced = 0;
    private final Set<String> energySpenders = new HashSet<String>();

    // Flight recorder (Wald): land-drop consistency for the tracked player.
    // ourTurns counts the tracked player's turns; ourLandDrops their land plays —
    // missed drops = ourTurns - ourLandDrops (mostly mana screw or empty hand).
    private int ourTurns = 0;
    private int ourLandDrops = 0;

    // Flight recorder: opponent COMMANDER casts and whether we could/did answer them.
    // Keyed by the commander card id while on the stack; value = snapshot
    // "seq\tname\tcountersHeld\tcreatureCapable\tuntappedLands"; on stack exit the
    // outcome (RESOLVED/DENIED) is appended and the row moves to commanderCastLog.
    private int oppCommanderCastSeq = 0;
    private final Map<Integer, String> pendingCommanderCasts = new HashMap<Integer, String>();
    private final List<String> commanderCastLog = new ArrayList<String>();

    /** Completed opponent-commander-cast rows: seq, name, countersHeld, creatureCapable, untappedLands, outcome. */
    public List<String> getCommanderCastLog() {
        return commanderCastLog;
    }

    // Flight recorder: every counterspell WE cast and what it targeted —
    // "ourCounter\ttargetName\ttgtIsCommander\ttgtIsCreature\ttgtCMC\tcommanderLoomable".
    private final List<String> counterCastLog = new ArrayList<String>();

    /** Rows for each counterspell we cast: target + whether a commander was loomable. */
    public List<String> getCounterCastLog() {
        return counterCastLog;
    }

    public int getOurTurns() {
        return ourTurns;
    }

    public int getOurLandDrops() {
        return ourLandDrops;
    }

    public CardPerformanceTracker(Player trackedPlayer) {
        this.trackedPlayer = trackedPlayer;
    }

    private CardStats getOrCreateStats(String cardName) {
        CardStats stats = cardStats.get(cardName);
        if (stats == null) {
            stats = new CardStats();
            cardStats.put(cardName, stats);
        }
        return stats;
    }

    /** True if the view refers to the tracked player (views carry the game player's id). */
    private boolean isTrackedPlayer(PlayerView view) {
        return view != null && view.getId() == trackedPlayer.getId();
    }

    /**
     * Resolves a CardView carried by a game event back to the live game Card.
     * Events fire synchronously on the game thread (Guava EventBus), so the card is
     * still in one of the game zones at handling time.
     */
    private Card resolveCard(CardView view) {
        if (view == null) {
            return null;
        }
        try {
            Game game = trackedPlayer.getGame();
            return game == null ? null : game.findById(view.getId());
        } catch (Exception e) {
            return null; // Card state may be in transition
        }
    }

    /**
     * Resolves the stack-item view carried by the cast event back to the live SpellAbility.
     * The event fires synchronously right after the item is pushed onto the stack, so the
     * matching SpellAbilityStackInstance is still present.
     */
    private SpellAbility resolveSpellAbility(StackItemView siView) {
        if (siView == null) {
            return null;
        }
        try {
            Game game = trackedPlayer.getGame();
            if (game == null) {
                return null;
            }
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (si.getId() == siView.getId()) {
                    return si.getSpellAbility();
                }
            }
        } catch (Exception e) {
            // Stack may be in transition
        }
        return null;
    }

    /**
     * Attribution name for a permanent. A cloned/copied permanent (e.g. Aurora Shifter or
     * Spark Double that has "become a copy of" another creature) is credited under
     * "&lt;original card name&gt; - &lt;copied target name&gt;", so the copy-maker gets credit for
     * what its copy actually does on the battlefield instead of that value silently landing
     * under the copied card's name. Non-clones return their plain name. Name-preserving clones
     * (e.g. Irma, which keeps her own name via NewName) already carry their own credit, so they
     * fall back to the plain name unless the copied target can be recovered.
     */
    private static String trackName(Card c) {
        try {
            if (c.isCloned()) {
                CardState orig = c.getOriginalState(CardStateName.Original);
                String original = orig != null ? orig.getName() : null;
                String copied = c.getName();
                if (original != null && !original.isEmpty()) {
                    if (!original.equals(copied)) {
                        return original + " - " + copied;
                    }
                    // Name-preserving clone (NewName): recover the copied target if available.
                    Card origin = c.getCloneOrigin();
                    if (origin != null && origin.getName() != null
                            && !origin.getName().equals(original)) {
                        return original + " - " + origin.getName();
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to plain name on any state-transition hiccup.
        }
        return c.getName();
    }

    /**
     * Entry point for all game events via Guava EventBus.
     */
    @Subscribe
    public void receive(GameEvent ev) {
        if (ev instanceof GameEventPlayerDamaged) {
            handlePlayerDamaged((GameEventPlayerDamaged) ev);
        } else if (ev instanceof GameEventSpellAbilityCast) {
            handleSpellCast((GameEventSpellAbilityCast) ev);
        } else if (ev instanceof GameEventBlockersDeclared) {
            handleBlockersDeclared((GameEventBlockersDeclared) ev);
        } else if (ev instanceof GameEventCardChangeZone) {
            handleCardChangeZone((GameEventCardChangeZone) ev);
        } else if (ev instanceof GameEventLandPlayed) {
            handleLandPlayed((GameEventLandPlayed) ev);
        } else if (ev instanceof GameEventTurnBegan) {
            handleTurnBegan((GameEventTurnBegan) ev);
        } else if (ev instanceof GameEventCardCounters) {
            handleCardCounters((GameEventCardCounters) ev);
        } else if (ev instanceof GameEventPlayerCounters) {
            handlePlayerCounters((GameEventPlayerCounters) ev);
        }
    }

    /**
     * Tracks direct damage, mana attribution, buff attribution, and token parent credit.
     */
    private void handlePlayerDamaged(GameEventPlayerDamaged ev) {
        Card source = resolveCard(ev.source());
        if (source == null || source.getController() == null) {
            return;
        }
        // Only track damage dealt by our player to opponents
        if (!source.getController().equals(trackedPlayer)) {
            return;
        }
        if (isTrackedPlayer(ev.target())) {
            return; // Don't count self-damage
        }

        int amount = ev.amount();
        if (amount <= 0) {
            return;
        }

        // Credit copies (clones) under "<original> - <copied target>" so copy-makers
        // (Aurora Shifter, Spark Double, ...) get credit for what their copies do.
        String sourceName = trackName(source);

        // 1. Direct damage credit
        getOrCreateStats(sourceName).damageDealt += amount;

        // 2. Mana attribution - credit mana sources that paid for this creature/spell
        Set<String> manaSources = castManaSourcesMap.get(sourceName);
        if (manaSources != null && !manaSources.isEmpty()) {
            double creditPerSource = (double) amount / manaSources.size();
            for (String manaSourceName : manaSources) {
                getOrCreateStats(manaSourceName).manaCredit += creditPerSource;
            }
        }

        // 3. Buff attribution - credit equipment/auras attached to this creature
        try {
            int attachmentCount = 0;
            for (Card attached : source.getEquippedBy()) {
                if (attached.getController().equals(trackedPlayer)) {
                    attachmentCount++;
                }
            }
            for (Card attached : source.getAttachedCards()) {
                if (attached.isAura() && attached.getController().equals(trackedPlayer)
                        && !attached.isEquipment()) {
                    attachmentCount++;
                }
            }

            if (attachmentCount > 0) {
                double buffShare = amount * 0.3 / attachmentCount;
                for (Card attached : source.getEquippedBy()) {
                    if (attached.getController().equals(trackedPlayer)) {
                        getOrCreateStats(attached.getName()).buffCredit += buffShare;
                        // 3a. Mana attribution for equipment
                        Set<String> eqManaSources = castManaSourcesMap.get(attached.getName());
                        if (eqManaSources != null && !eqManaSources.isEmpty()) {
                            double eqManaCredit = buffShare / eqManaSources.size();
                            for (String manaSourceName : eqManaSources) {
                                getOrCreateStats(manaSourceName).manaCredit += eqManaCredit;
                            }
                        }
                        // 3b. Equipment token parent credit
                        if (attached.isToken()) {
                            try {
                                SpellAbility spawning = attached.getTokenSpawningAbility();
                                if (spawning != null) {
                                    Card eqCreator = spawning.getHostCard();
                                    if (eqCreator != null && eqCreator.getOwner() != null
                                            && eqCreator.getOwner().equals(trackedPlayer)) {
                                        String eqCreatorName = eqCreator.getName();
                                        if (!eqCreatorName.equals(attached.getName())) {
                                            getOrCreateStats(eqCreatorName).buffCredit += buffShare * 0.5;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore - token equipment state may be in transition
                            }
                        }
                    }
                }
                for (Card attached : source.getAttachedCards()) {
                    if (attached.isAura() && attached.getController().equals(trackedPlayer)
                            && !attached.isEquipment()) {
                        getOrCreateStats(attached.getName()).buffCredit += buffShare;
                        // Mana attribution for auras
                        Set<String> auraManaSources = castManaSourcesMap.get(attached.getName());
                        if (auraManaSources != null && !auraManaSources.isEmpty()) {
                            double auraManaCredit = buffShare / auraManaSources.size();
                            for (String manaSourceName : auraManaSources) {
                                getOrCreateStats(manaSourceName).manaCredit += auraManaCredit;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore attachment errors - card state may be in transition
        }

        // 4. Token parent credit - if this damage source is a token, credit its creator
        if (source.isToken()) {
            try {
                SpellAbility spawning = source.getTokenSpawningAbility();
                if (spawning != null) {
                    Card creator = spawning.getHostCard();
                    if (creator != null && creator.getOwner() != null
                            && creator.getOwner().equals(trackedPlayer)) {
                        String creatorName = creator.getName();
                        if (!creatorName.equals(sourceName)) {
                            // Give creator 50% credit for the token's damage
                            getOrCreateStats(creatorName).damageDealt += amount * 0.5;
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore - token state may be in transition
            }
        }

        // 5. Quest counter source credit
        if (totalQuestCountersPlaced > 0) {
            try {
                boolean hasEquipment = !source.getEquippedBy().isEmpty();
                if (hasEquipment) {
                    // Count current quest counters on our battlefield
                    int currentQuestCounters = 0;
                    for (Card perm : trackedPlayer.getZone(ZoneType.Battlefield)) {
                        currentQuestCounters += perm.getCounters(CounterEnumType.QUEST);
                    }
                    if (currentQuestCounters > 0) {
                        int netPower = source.getNetPower();
                        if (netPower > 0) {
                            double questRatio = Math.min(currentQuestCounters, netPower)
                                    / (double) netPower;
                            double totalCredit = amount * questRatio * 0.3;
                            for (Map.Entry<String, Integer> entry : questCounterSources.entrySet()) {
                                double share = totalCredit * entry.getValue()
                                        / totalQuestCountersPlaced;
                                getOrCreateStats(entry.getKey()).buffCredit += share;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore - quest counter attribution errors
            }
        }

        // 6. Energy source credit - if the damage source has spent energy, credit producers
        if (totalEnergyProduced > 0 && energySpenders.contains(sourceName)) {
            try {
                double energyCredit = amount * 0.5;
                for (Map.Entry<String, Integer> entry : energyProducers.entrySet()) {
                    double share = energyCredit * entry.getValue() / totalEnergyProduced;
                    getOrCreateStats(entry.getKey()).manaCredit += share;
                }
            } catch (Exception e) {
                // Ignore - energy attribution errors
            }
        }
    }

    /**
     * Records which mana sources paid for each spell we cast.
     * Also records targets for removal attribution and cost reduction credit.
     */
    private void handleSpellCast(GameEventSpellAbilityCast ev) {
        // The event carries views; resolve the live SpellAbility from the game stack.
        SpellAbility sa = resolveSpellAbility(ev.si());
        if (sa == null || sa.getActivatingPlayer() == null) {
            return;
        }
        if (!sa.getActivatingPlayer().equals(trackedPlayer)) {
            // Flight recorder: an OPPONENT casting their commander is the highest-leverage
            // counter decision in the game. Snapshot our ability to answer it at this exact
            // moment (counters in hand, creature-capable counters, untapped lands); the
            // outcome (resolved vs denied) is filled in when the spell leaves the stack
            // (handleCardChangeZone), mirroring the counter-attribution machinery.
            try {
                Card oppHost = sa.getHostCard();
                if (sa.isSpell() && oppHost != null && oppHost.isCommander()) {
                    int countersHeld = 0;
                    int creatureCapable = 0;
                    for (Card c : trackedPlayer.getCardsIn(ZoneType.Hand)) {
                        for (SpellAbility csa : c.getBasicSpells()) {
                            if (csa.getApi() == ApiType.Counter) {
                                countersHeld++;
                                String validTgts = csa.getParamOrDefault("ValidTgts", "Card");
                                if (!validTgts.contains("nonCreature") && !validTgts.contains("Noncreature")
                                        && (validTgts.contains("Card") || validTgts.contains("Creature"))) {
                                    creatureCapable++;
                                }
                                break;
                            }
                        }
                    }
                    int untappedLands = 0;
                    for (Card land : trackedPlayer.getLandsInPlay()) {
                        if (!land.isTapped()) {
                            untappedLands++;
                        }
                    }
                    oppCommanderCastSeq++;
                    pendingCommanderCasts.put(oppHost.getId(), oppCommanderCastSeq + "\t" + oppHost.getName()
                            + "\t" + countersHeld + "\t" + creatureCapable + "\t" + untappedLands);
                }
            } catch (Exception e) {
                // recorder must never break tracking
            }
            return;
        }
        if (!sa.isSpell()) {
            return;
        }

        Card hostCard = sa.getHostCard();
        if (hostCard == null) {
            return;
        }

        // Flight recorder: when WE cast a counterspell, what is it aimed at? Tests whether
        // the AI burns counters on non-commander spells while an opponent commander is
        // loomable in the command zone (i.e. counter misallocation, not reservation size).
        try {
            if (sa.getApi() == ApiType.Counter) {
                Card tgt = null;
                for (SpellAbility tgtSpell : sa.getTargets().getTargetSpells()) {
                    if (tgtSpell != null && tgtSpell.getHostCard() != null) {
                        tgt = tgtSpell.getHostCard();
                        break;
                    }
                }
                boolean tgtIsCommander = tgt != null && tgt.isCommander();
                boolean tgtIsCreature = tgt != null && tgt.isCreature();
                int tgtCMC = tgt != null ? tgt.getCMC() : -1;
                // Is an opponent commander loomable (in the command zone) right now —
                // i.e. could this counter have been saved for it?
                boolean commanderLoomable = false;
                for (Player opp : trackedPlayer.getOpponents()) {
                    for (Card cmd : opp.getCommanders()) {
                        if (cmd.isInZone(ZoneType.Command)) {
                            commanderLoomable = true;
                            break;
                        }
                    }
                    if (commanderLoomable) {
                        break;
                    }
                }
                counterCastLog.add(sa.getHostCard().getName() + "\t"
                        + (tgt != null ? tgt.getName() : "-") + "\t" + tgtIsCommander
                        + "\t" + tgtIsCreature + "\t" + tgtCMC + "\t" + commanderLoomable);
            }
        } catch (Exception e) {
            // recorder must never break tracking
        }

        String cardName = hostCard.getName();
        // Ensure this card has a stats entry and mark as played
        getOrCreateStats(cardName).played = true;

        // Record mana sources
        List<Mana> payingMana = sa.getPayingMana();
        int manaPaid = 0;
        if (payingMana != null && !payingMana.isEmpty()) {
            manaPaid = payingMana.size();
            Set<String> sources = new HashSet<String>();
            for (Mana m : payingMana) {
                Card sourceCard = m.sourceCard();
                if (sourceCard != null) {
                    sources.add(sourceCard.getName());
                    getOrCreateStats(sourceCard.getName());
                }
            }
            if (!sources.isEmpty()) {
                castManaSourcesMap.put(cardName, sources);
            }
        }

        // Cost reduction credit - if mana paid < CMC, credit cost reducers
        try {
            int cmc = hostCard.getCMC();
            if (cmc > manaPaid && manaPaid >= 0) {
                int savedMana = cmc - manaPaid;
                // Find cost reducers on our battlefield
                List<String> costReducers = new ArrayList<String>();
                for (Card perm : trackedPlayer.getZone(ZoneType.Battlefield)) {
                    if (perm.getController().equals(trackedPlayer)) {
                        for (StaticAbility stAb : perm.getStaticAbilities()) {
                            if (stAb.checkMode(StaticAbilityMode.ReduceCost)) {
                                costReducers.add(perm.getName());
                                break;
                            }
                        }
                    }
                }
                if (!costReducers.isEmpty()) {
                    double creditPerReducer = (double) savedMana / costReducers.size();
                    for (String reducerName : costReducers) {
                        getOrCreateStats(reducerName).manaCredit += creditPerReducer;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore - cost calculation may fail during unusual game states
        }

        // Record targets for removal attribution
        try {
            CardCollectionView targetCards = sa.getTargets().getTargetCards();
            if (targetCards != null) {
                for (Card target : targetCards) {
                    if (target.getController() != null
                            && !target.getController().equals(trackedPlayer)
                            && target.isCreature()) {
                        pendingRemovalTargets.put(target.getId(), cardName);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore - targets may not be available
        }

        // Counter attribution (phase 1 of 2): if we counter an opponent CREATURE spell, only
        // RECORD a pending note here — the actual score is added at resolution (handleCardChangeZone)
        // and ONLY if the creature truly leaves the Stack to GY/Exile. So if our counter is itself
        // countered (the creature then resolves to the Battlefield), nothing is credited.
        // Value = largest of threat-level[1-6], mana value, power, toughness.
        try {
            if (sa.getApi() == ApiType.Counter) {
                for (SpellAbility tgtSpell : sa.getTargets().getTargetSpells()) {
                    Card countered = tgtSpell == null ? null : tgtSpell.getHostCard();
                    if (countered != null && countered.isCreature()
                            && countered.getController() != null
                            && !countered.getController().equals(trackedPlayer)) {
                        int eval = ComputerUtilCard.evaluateCreature(countered);
                        int threat = Math.max(1, Math.min(6, (int) Math.round(eval / 100.0)));
                        int value = Math.max(Math.max(threat, countered.getCMC()),
                                Math.max(countered.getNetPower(), countered.getNetToughness()));
                        if (value < 1) {
                            value = 1;
                        }
                        pendingCounterTargets.put(countered.getId(), cardName);
                        pendingCounterValues.put(countered.getId(), value);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore - counter targets/eval may be unavailable
        }
    }

    /**
     * Credits our blockers with damage prevented (attacker's power at block time).
     */
    private void handleBlockersDeclared(GameEventBlockersDeclared ev) {
        if (!isTrackedPlayer(ev.defendingPlayer())) {
            return;
        }

        Map<GameEntityView, Multimap<CardView, CardView>> blockersMap = ev.blockers();
        if (blockersMap == null) {
            return;
        }

        for (Map.Entry<GameEntityView, Multimap<CardView, CardView>> entityEntry : blockersMap.entrySet()) {
            Multimap<CardView, CardView> attackerToBlockers = entityEntry.getValue();
            if (attackerToBlockers == null) {
                continue;
            }

            for (Map.Entry<CardView, Collection<CardView>> entry : attackerToBlockers.asMap().entrySet()) {
                Card attacker = resolveCard(entry.getKey());
                Collection<CardView> blockerViews = entry.getValue();
                if (attacker == null || blockerViews == null || blockerViews.isEmpty()) {
                    continue;
                }
                // Resolve blocker views back to live Cards (event fires synchronously).
                List<Card> blockers = new ArrayList<Card>();
                for (CardView blockerView : blockerViews) {
                    Card blocker = resolveCard(blockerView);
                    if (blocker != null) {
                        blockers.add(blocker);
                    }
                }
                if (blockers.isEmpty()) {
                    continue;
                }

                int attackerPower = attacker.getNetPower();
                if (attackerPower <= 0) {
                    continue;
                }

                int ourBlockerCount = 0;
                for (Card blocker : blockers) {
                    if (blocker.getController().equals(trackedPlayer)) {
                        ourBlockerCount++;
                    }
                }

                if (ourBlockerCount > 0) {
                    double creditPerBlocker = (double) attackerPower / ourBlockerCount;
                    for (Card blocker : blockers) {
                        if (blocker.getController().equals(trackedPlayer)) {
                            getOrCreateStats(trackName(blocker)).blockingDamage += creditPerBlocker;
                            // Token blocking parent credit
                            if (blocker.isToken()) {
                                try {
                                    SpellAbility spawning = blocker.getTokenSpawningAbility();
                                    if (spawning != null) {
                                        Card creator = spawning.getHostCard();
                                        if (creator != null && creator.getOwner() != null
                                                && creator.getOwner().equals(trackedPlayer)) {
                                            String creatorName = creator.getName();
                                            if (!creatorName.equals(blocker.getName())) {
                                                getOrCreateStats(creatorName).blockingDamage
                                                        += creditPerBlocker * 0.5;
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    // Ignore - token state may be in transition
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Tracks card draws (Library -> Hand) and removal attribution
     * (opponent creature leaving battlefield after being targeted by our spell).
     */
    private void handleCardChangeZone(GameEventCardChangeZone ev) {
        CardView card = ev.card();
        if (card == null) {
            return;
        }
        ZoneView from = ev.from();
        ZoneView to = ev.to();
        if (from == null || to == null) {
            return;
        }

        // Track draws: Library -> Hand for our player
        if (from.zoneType() == ZoneType.Library && to.zoneType() == ZoneType.Hand) {
            if (isTrackedPlayer(card.getOwner())) {
                getOrCreateStats(card.getName());
            }
        }

        // Removal attribution: opponent creature leaving battlefield
        if (from.zoneType() == ZoneType.Battlefield
                && (to.zoneType() == ZoneType.Graveyard || to.zoneType() == ZoneType.Exile)) {
            if (card.getOwner() != null && !isTrackedPlayer(card.getOwner())
                    && card.getCurrentState() != null && card.getCurrentState().isCreature()) {
                int creatureId = card.getId();
                String ourSpellName = pendingRemovalTargets.remove(creatureId);
                if (ourSpellName != null) {
                    int power = card.getCurrentState().getPower();
                    if (power < 1) {
                        power = 1; // Minimum 1 credit for removing any creature
                    }
                    getOrCreateStats(ourSpellName).removalValue += power;
                    // Mana attribution for removal spells
                    Set<String> manaSources = castManaSourcesMap.get(ourSpellName);
                    if (manaSources != null && !manaSources.isEmpty()) {
                        double creditPerSource = (double) power / manaSources.size();
                        for (String manaSourceName : manaSources) {
                            getOrCreateStats(manaSourceName).manaCredit += creditPerSource;
                        }
                    }
                }
            }
        }

        // Flight recorder: an opponent commander spell leaves the stack — record the outcome.
        // Battlefield = RESOLVED (we failed/declined to stop it); anywhere else (command zone
        // after being countered, graveyard, exile, hand, library) = DENIED.
        if (from.zoneType() == ZoneType.Stack && pendingCommanderCasts.containsKey(card.getId())) {
            String snapshot = pendingCommanderCasts.remove(card.getId());
            boolean resolved = to.zoneType() == ZoneType.Battlefield;
            commanderCastLog.add(snapshot + "\t" + (resolved ? "RESOLVED" : "DENIED"));
        }

        // Counter attribution (phase 2 of 2): an opponent CREATURE spell we countered leaves the
        // Stack. Credit the counterspell ONLY if it actually went to GY/Exile (i.e. was countered);
        // if it resolved to the Battlefield instead (our counter was itself countered/fizzled), the
        // pending note is dropped with no credit.
        if (from.zoneType() == ZoneType.Stack && pendingCounterTargets.containsKey(card.getId())) {
            String counterName = pendingCounterTargets.remove(card.getId());
            Integer counterValue = pendingCounterValues.remove(card.getId());
            boolean wasCountered = to.zoneType() == ZoneType.Graveyard || to.zoneType() == ZoneType.Exile;
            if (wasCountered && counterName != null && counterValue != null) {
                getOrCreateStats(counterName).removalValue += counterValue;
                Set<String> manaSources = castManaSourcesMap.get(counterName);
                if (manaSources != null && !manaSources.isEmpty()) {
                    double creditPerSource = (double) counterValue / manaSources.size();
                    for (String manaSourceName : manaSources) {
                        getOrCreateStats(manaSourceName).manaCredit += creditPerSource;
                    }
                }
            }
        }
    }

    /**
     * Peeks the game stack to find the source card of the currently resolving ability
     * belonging to our tracked player. Returns the card name, or null if not found.
     */
    private String peekStackForSource() {
        try {
            Game game = trackedPlayer.getGame();
            if (game == null) {
                return null;
            }
            MagicStack stack = game.getStack();
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            for (SpellAbilityStackInstance si : stack) {
                if (si.getActivatingPlayer().equals(trackedPlayer)) {
                    Card sourceCard = si.getSourceCard();
                    if (sourceCard != null) {
                        return sourceCard.getName();
                    }
                }
            }
        } catch (Exception e) {
            // Stack may be in transition
        }
        return null;
    }

    /**
     * Tracks quest counter placements on our permanents for buff attribution.
     */
    private void handleCardCounters(GameEventCardCounters ev) {
        if (ev.newValue() <= ev.oldValue()) {
            return; // Only track counter additions
        }
        CounterType type = ev.type();
        if (type == null || !type.is(CounterEnumType.QUEST)) {
            return;
        }
        CardView card = ev.card();
        if (card == null || !isTrackedPlayer(card.getController())) {
            return;
        }

        int added = ev.newValue() - ev.oldValue();
        String sourceName = peekStackForSource();
        if (sourceName == null) {
            sourceName = card.getName(); // Fallback: credit the card receiving counters
        }

        Integer current = questCounterSources.get(sourceName);
        questCounterSources.put(sourceName, (current == null ? 0 : current) + added);
        totalQuestCountersPlaced += added;
    }

    /**
     * Tracks energy production and spending for our player.
     * Note: GameEventPlayerCounters(player, type, oldValue, amount) where
     * amount() is actually the NEW counter value (not a delta).
     */
    private void handlePlayerCounters(GameEventPlayerCounters ev) {
        if (!isTrackedPlayer(ev.receiver())) {
            return;
        }
        CounterType type = ev.type();
        if (type == null || !type.is(CounterEnumType.ENERGY)) {
            return;
        }

        int oldValue = ev.oldValue();
        int newValue = ev.amount(); // misleadingly named; this is the new value
        if (newValue > oldValue) {
            // Energy produced
            int produced = newValue - oldValue;
            String sourceName = peekStackForSource();
            if (sourceName != null) {
                Integer current = energyProducers.get(sourceName);
                energyProducers.put(sourceName, (current == null ? 0 : current) + produced);
                totalEnergyProduced += produced;
            }
        } else if (newValue < oldValue) {
            // Energy spent
            String spenderName = peekStackForSource();
            if (spenderName != null) {
                energySpenders.add(spenderName);
            }
        }
    }

    /**
     * Registers lands played by our player so they appear in the report.
     */
    private void handleLandPlayed(GameEventLandPlayed ev) {
        if (isTrackedPlayer(ev.player())) {
            ourLandDrops++;
            CardView land = ev.land();
            if (land != null) {
                getOrCreateStats(land.getName()).played = true;
            }
        }
    }

    /** Counts the tracked player's turns for missed-land-drop accounting. */
    private void handleTurnBegan(GameEventTurnBegan ev) {
        if (isTrackedPlayer(ev.turnOwner())) {
            ourTurns++;
        }
    }

    /** Returns the per-card stats map for aggregation after the game ends. */
    public Map<String, CardStats> getStats() {
        return cardStats;
    }

    /** Returns the tracked player. */
    public Player getTrackedPlayer() {
        return trackedPlayer;
    }
}
