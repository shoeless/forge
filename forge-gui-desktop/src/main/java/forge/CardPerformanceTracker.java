package forge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import forge.game.zone.Zone;
import forge.util.maps.MapOfLists;

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
        }
    }

    /**
     * Tracks direct damage, mana attribution, buff attribution, and token parent credit.
     */
    private void handlePlayerDamaged(GameEventPlayerDamaged ev) {
        Card source = ev.source();
        if (source == null || source.getController() == null) {
            return;
        }
        // Only track damage dealt by our player to opponents
        if (!source.getController().equals(trackedPlayer)) {
            return;
        }
        if (ev.target().equals(trackedPlayer)) {
            return; // Don't count self-damage
        }

        int amount = ev.amount();
        if (amount <= 0) {
            return;
        }

        String sourceName = source.getName();

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
                    }
                }
                for (Card attached : source.getAttachedCards()) {
                    if (attached.isAura() && attached.getController().equals(trackedPlayer)
                            && !attached.isEquipment()) {
                        getOrCreateStats(attached.getName()).buffCredit += buffShare;
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
    }

    /**
     * Records which mana sources paid for each spell we cast.
     * Also records targets for removal attribution and cost reduction credit.
     */
    private void handleSpellCast(GameEventSpellAbilityCast ev) {
        SpellAbility sa = ev.sa();
        if (sa == null || sa.getActivatingPlayer() == null) {
            return;
        }
        if (!sa.getActivatingPlayer().equals(trackedPlayer)) {
            return;
        }
        if (!sa.isSpell()) {
            return;
        }

        Card hostCard = sa.getHostCard();
        if (hostCard == null) {
            return;
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
    }

    /**
     * Credits our blockers with damage prevented (attacker's power at block time).
     */
    private void handleBlockersDeclared(GameEventBlockersDeclared ev) {
        if (!ev.defendingPlayer().equals(trackedPlayer)) {
            return;
        }

        Map<GameEntity, MapOfLists<Card, Card>> blockersMap = ev.blockers();
        if (blockersMap == null) {
            return;
        }

        for (Map.Entry<GameEntity, MapOfLists<Card, Card>> entityEntry : blockersMap.entrySet()) {
            MapOfLists<Card, Card> attackerToBlockers = entityEntry.getValue();
            if (attackerToBlockers == null) {
                continue;
            }

            for (Map.Entry<Card, Collection<Card>> entry : attackerToBlockers.entrySet()) {
                Card attacker = entry.getKey();
                Collection<Card> blockers = entry.getValue();
                if (attacker == null || blockers == null || blockers.isEmpty()) {
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
                            getOrCreateStats(blocker.getName()).blockingDamage += creditPerBlocker;
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
        Card card = ev.card();
        if (card == null) {
            return;
        }
        Zone from = ev.from();
        Zone to = ev.to();
        if (from == null || to == null) {
            return;
        }

        // Track draws: Library -> Hand for our player
        if (from.getZoneType() == ZoneType.Library && to.getZoneType() == ZoneType.Hand) {
            if (card.getOwner() != null && card.getOwner().equals(trackedPlayer)) {
                getOrCreateStats(card.getName());
            }
        }

        // Removal attribution: opponent creature leaving battlefield
        if (from.getZoneType() == ZoneType.Battlefield
                && (to.getZoneType() == ZoneType.Graveyard || to.getZoneType() == ZoneType.Exile)) {
            if (card.getOwner() != null && !card.getOwner().equals(trackedPlayer)
                    && card.isCreature()) {
                int creatureId = card.getId();
                String ourSpellName = pendingRemovalTargets.remove(creatureId);
                if (ourSpellName != null) {
                    int power = card.getNetPower();
                    if (power < 1) {
                        power = 1; // Minimum 1 credit for removing any creature
                    }
                    getOrCreateStats(ourSpellName).removalValue += power;
                }
            }
        }
    }

    /**
     * Registers lands played by our player so they appear in the report.
     */
    private void handleLandPlayed(GameEventLandPlayed ev) {
        if (ev.player() != null && ev.player().equals(trackedPlayer)) {
            Card land = ev.land();
            if (land != null) {
                getOrCreateStats(land.getName()).played = true;
            }
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
