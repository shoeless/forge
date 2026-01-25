package forge.game;

import java.util.Map;

import org.apache.commons.lang3.ObjectUtils;

import com.google.common.collect.ForwardingTable;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.util.MapUtil;

// iOS compatibility: Replaced Optional<Player> with nullable Player (null = "any player")
// Since HashBasedTable doesn't support null keys, we store null-player entries separately
public class GameEntityCounterTable extends ForwardingTable<Player, GameEntity, Map<CounterType, Integer>> {

    private Table<Player, GameEntity, Map<CounterType, Integer>> dataMap = HashBasedTable.create();
    // Separate storage for entries where player is null (meaning "any player")
    private Map<GameEntity, Map<CounterType, Integer>> nullPlayerMap = Maps.newHashMap();

    public GameEntityCounterTable() {
    }

    public GameEntityCounterTable(Table<Player, GameEntity, Map<CounterType, Integer>> counterTable) {
        putAll(counterTable);
    }

    /*
     * (non-Javadoc)
     * @see com.google.common.collect.ForwardingTable#delegate()
     */
    @Override
    protected Table<Player, GameEntity, Map<CounterType, Integer>> delegate() {
        return dataMap;
    }

    public Integer put(Player putter, GameEntity object, CounterType type, Integer value) {
        // iOS compatibility: putter can be null (means "any player")
        // Use separate map for null player since HashBasedTable doesn't support null keys
        Map<CounterType, Integer> map;
        if (putter == null) {
            map = nullPlayerMap.get(object);
            if (map == null) {
                map = Maps.newHashMap();
                nullPlayerMap.put(object, map);
            }
        } else {
            map = get(putter, object);
            if (map == null) {
                map = Maps.newHashMap();
                put(putter, object, map);
            }
        }
        return map.put(type, ObjectUtils.firstNonNull(map.get(type), 0) + value);
    }

    public int get(Player putter, GameEntity object, CounterType type) {
        // iOS compatibility: putter can be null (means "any player")
        // Use separate map for null player since HashBasedTable doesn't support null keys
        Map<CounterType, Integer> map;
        if (putter == null) {
            map = nullPlayerMap.get(object);
        } else {
            map = get(putter, object);
        }
        if (map == null || !map.containsKey(type)) {
            return 0;
        }
        return ObjectUtils.firstNonNull(map.get(type), 0);
    }

    @Override
    public boolean isEmpty() {
        return dataMap.isEmpty() && nullPlayerMap.isEmpty();
    }

    @Override
    public boolean containsColumn(Object columnKey) {
        return dataMap.containsColumn(columnKey) || nullPlayerMap.containsKey(columnKey);
    }

    @Override
    public Map<GameEntity, Map<CounterType, Integer>> row(Player rowKey) {
        // Handle null row key by returning the nullPlayerMap
        if (rowKey == null) {
            return nullPlayerMap;
        }
        return dataMap.row(rowKey);
    }

    public int totalValues() {
        int result = 0;
        for (Map<CounterType, Integer> m : values()) {
            for (Integer i : m.values()) {
                result += i;
            }
        }
        // Also count values from nullPlayerMap
        for (Map<CounterType, Integer> m : nullPlayerMap.values()) {
            for (Integer i : m.values()) {
                result += i;
            }
        }
        return result;
    }

    /*
     * returns the counters that can still be removed from game entity
     */
    public Map<CounterType, Integer> filterToRemove(GameEntity ge) {
        Map<CounterType, Integer> result = Maps.newHashMap();
        if (!containsColumn(ge) && !nullPlayerMap.containsKey(ge)) {
            result.putAll(ge.getCounters());
            return result;
        }
        // iOS compatibility: Use separate nullPlayerMap instead of column(ge).get(null)
        Map<CounterType, Integer> alreadyRemoved = nullPlayerMap.get(ge);
        for (Map.Entry<CounterType, Integer> e : ge.getCounters().entrySet()) {
            // iOS compatibility: Replace getOrDefault (Java 8 Map method)
            int rest = e.getValue() - MapUtil.getOrDefault(alreadyRemoved, e.getKey(), 0);
            if (rest > 0) {
                result.put(e.getKey(), rest);
            }
        }
        return result;
    }

    public Map<GameEntity, Integer> filterTable(CounterType type, String valid, Card host, CardTraitBase sa) {
        Map<GameEntity, Integer> result = Maps.newHashMap();

        // iOS compatibility: Player instead of Optional<Player>
        for (Map.Entry<GameEntity, Map<Player, Map<CounterType, Integer>>> gm : columnMap().entrySet()) {
            if (gm.getKey().isValid(valid, host.getController(), host, sa)) {
                for (Map<CounterType, Integer> cm : gm.getValue().values()) {
                    Integer old = ObjectUtils.firstNonNull(result.get(gm.getKey()), 0);
                    Integer v = ObjectUtils.firstNonNull(cm.get(type), 0);
                    if (old + v > 0) {
                        result.put(gm.getKey(), old + v);
                    }
                }
            }
        }
        // Also include entries from nullPlayerMap (where player is null/"any player")
        for (Map.Entry<GameEntity, Map<CounterType, Integer>> entry : nullPlayerMap.entrySet()) {
            if (entry.getKey().isValid(valid, host.getController(), host, sa)) {
                Integer old = ObjectUtils.firstNonNull(result.get(entry.getKey()), 0);
                Integer v = ObjectUtils.firstNonNull(entry.getValue().get(type), 0);
                if (old + v > 0) {
                    result.put(entry.getKey(), old + v);
                }
            }
        }
        return result;
    }

    public void triggerCountersPutAll(final Game game) {
        if (isEmpty()) {
            return;
        }
        // iOS compatibility: Player instead of Optional<Player>
        for (Cell<Player, GameEntity, Map<CounterType, Integer>> c : cellSet()) {
            if (c.getValue().isEmpty()) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
            // iOS compatibility: getRowKey() is now nullable Player
            runParams.put(AbilityKey.Source, c.getRowKey());
            runParams.put(AbilityKey.Object, c.getColumnKey());
            runParams.put(AbilityKey.CounterMap, c.getValue());
            game.getTriggerHandler().runTrigger(TriggerType.CounterPlayerAddedAll, runParams, false);
        }
        // Also process entries from nullPlayerMap (where player is null/"any player")
        for (Map.Entry<GameEntity, Map<CounterType, Integer>> entry : nullPlayerMap.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
            runParams.put(AbilityKey.Source, null);  // null player
            runParams.put(AbilityKey.Object, entry.getKey());
            runParams.put(AbilityKey.CounterMap, entry.getValue());
            game.getTriggerHandler().runTrigger(TriggerType.CounterPlayerAddedAll, runParams, false);
        }
        final Map<AbilityKey, Object> runParams = AbilityKey.newMap();
        runParams.put(AbilityKey.Objects, this);
        game.getTriggerHandler().runTrigger(TriggerType.CounterAddedAll, runParams, false);
    }

    public void replaceCounterEffect(final Game game, final SpellAbility cause, final boolean effect) {
        replaceCounterEffect(game, cause, effect, false, null);
    }

    @SuppressWarnings("unchecked")
    public boolean replaceCounterEffect(final Game game, final SpellAbility cause, final boolean effect, final boolean etb, Map<AbilityKey, Object> params) {
        if (isEmpty()) {
            return false;
        }
        GameEntityCounterTable result = new GameEntityCounterTable();
        // iOS compatibility: Player instead of Optional<Player>
        for (Map.Entry<GameEntity, Map<Player, Map<CounterType, Integer>>> gm : columnMap().entrySet()) {
            Map<Player, Map<CounterType, Integer>> values = gm.getValue();

            // ETB Counters are already handled in the Move Event
            if (!etb) {
                final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(gm.getKey());
                repParams.put(AbilityKey.Cause, cause);
                repParams.put(AbilityKey.EffectOnly, effect);
                repParams.put(AbilityKey.CounterMap, values);
                repParams.put(AbilityKey.ETB, etb);
                if (params != null) {
                    repParams.putAll(params);
                }

                switch (game.getReplacementHandler().run(ReplacementType.AddCounter, repParams)) {
                case NotReplaced:
                    break;
                case Updated: {
                    values = (Map<Player, Map<CounterType, Integer>>) repParams.get(AbilityKey.CounterMap);
                    break;
                }
                default:
                    continue;
                }
            }

            // Add ETB flag
            Map<AbilityKey, Object> runParams = AbilityKey.newMap();
            runParams.put(AbilityKey.Cause, cause);
            if (params != null) {
                runParams.putAll(params);
            }

            boolean firstTime = false;
            if (gm.getKey() instanceof Card) {
                Card c = (Card) gm.getKey();
                firstTime = game.getCounterAddedThisTurn(null, c) == 0;
            }

            // Apply counter after replacement effect
            // iOS compatibility: Player instead of Optional<Player>, using nullable Player
            for (Map.Entry<Player, Map<CounterType, Integer>> e : values.entrySet()) {
                boolean remember = cause != null && cause.hasParam("RememberPut");
                for (Map.Entry<CounterType, Integer> ec : e.getValue().entrySet()) {
                    Integer value = ec.getValue();
                    if (value == null) {
                        continue;
                    }
                    if (cause != null && cause.hasParam("MaxFromEffect")) {
                        value = Math.min(value, Integer.parseInt(cause.getParam("MaxFromEffect")) - gm.getKey().getCounters(ec.getKey()));
                    }
                    // iOS compatibility: e.getKey() is now nullable Player (null = "any player")
                    gm.getKey().addCounterInternal(ec.getKey(), value, e.getKey(), true, result, runParams);
                    if (remember && ec.getValue() > 0) {
                        cause.getHostCard().addRemembered(gm.getKey());
                    }
                }
            }

            if (result.containsColumn(gm.getKey())) {
                runParams = AbilityKey.newMap();
                runParams.put(AbilityKey.Object, gm.getKey());
                runParams.put(AbilityKey.FirstTime, firstTime);
                game.getTriggerHandler().runTrigger(TriggerType.CounterTypeAddedAll, runParams, false);
            }
        }

        // Also process entries from nullPlayerMap (where player is null/"any player")
        for (Map.Entry<GameEntity, Map<CounterType, Integer>> nullEntry : nullPlayerMap.entrySet()) {
            GameEntity ge = nullEntry.getKey();
            Map<CounterType, Integer> counterMap = nullEntry.getValue();

            // Wrap in format expected by replacement handler
            Map<Player, Map<CounterType, Integer>> values = Maps.newHashMap();
            values.put(null, counterMap);

            // ETB Counters are already handled in the Move Event
            if (!etb) {
                final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(ge);
                repParams.put(AbilityKey.Cause, cause);
                repParams.put(AbilityKey.EffectOnly, effect);
                repParams.put(AbilityKey.CounterMap, values);
                repParams.put(AbilityKey.ETB, etb);
                if (params != null) {
                    repParams.putAll(params);
                }

                switch (game.getReplacementHandler().run(ReplacementType.AddCounter, repParams)) {
                case NotReplaced:
                    break;
                case Updated: {
                    values = (Map<Player, Map<CounterType, Integer>>) repParams.get(AbilityKey.CounterMap);
                    break;
                }
                default:
                    continue;
                }
            }

            Map<AbilityKey, Object> runParams = AbilityKey.newMap();
            runParams.put(AbilityKey.Cause, cause);
            if (params != null) {
                runParams.putAll(params);
            }

            boolean firstTime = false;
            if (ge instanceof Card) {
                Card c = (Card) ge;
                firstTime = game.getCounterAddedThisTurn(null, c) == 0;
            }

            // Apply counter after replacement effect
            for (Map.Entry<Player, Map<CounterType, Integer>> e : values.entrySet()) {
                boolean remember = cause != null && cause.hasParam("RememberPut");
                for (Map.Entry<CounterType, Integer> ec : e.getValue().entrySet()) {
                    Integer value = ec.getValue();
                    if (value == null) {
                        continue;
                    }
                    if (cause != null && cause.hasParam("MaxFromEffect")) {
                        value = Math.min(value, Integer.parseInt(cause.getParam("MaxFromEffect")) - ge.getCounters(ec.getKey()));
                    }
                    ge.addCounterInternal(ec.getKey(), value, e.getKey(), true, result, runParams);
                    if (remember && ec.getValue() > 0) {
                        cause.getHostCard().addRemembered(ge);
                    }
                }
            }

            if (result.containsColumn(ge) || result.nullPlayerMap.containsKey(ge)) {
                runParams = AbilityKey.newMap();
                runParams.put(AbilityKey.Object, ge);
                runParams.put(AbilityKey.FirstTime, firstTime);
                game.getTriggerHandler().runTrigger(TriggerType.CounterTypeAddedAll, runParams, false);
            }
        }

        int totalAdded = totalValues();
        if (totalAdded > 0 && cause != null && cause.hasParam("RememberAmount")) {
            cause.getHostCard().addRemembered(totalAdded);
        }

        result.triggerCountersPutAll(game);
        return !result.isEmpty();
    }
}
