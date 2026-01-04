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
public class GameEntityCounterTable extends ForwardingTable<Player, GameEntity, Map<CounterType, Integer>> {

    private Table<Player, GameEntity, Map<CounterType, Integer>> dataMap = HashBasedTable.create();

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
        Map<CounterType, Integer> map = get(putter, object);
        if (map == null) {
            map = Maps.newHashMap();
            put(putter, object, map);
        }
        return map.put(type, ObjectUtils.firstNonNull(map.get(type), 0) + value);
    }

    public int get(Player putter, GameEntity object, CounterType type) {
        // iOS compatibility: putter can be null (means "any player")
        Map<CounterType, Integer> map = get(putter, object);
        if (map == null || !map.containsKey(type)) {
            return 0;
        }
        return ObjectUtils.firstNonNull(map.get(type), 0);
    }

    public int totalValues() {
        int result = 0;
        for (Map<CounterType, Integer> m : values()) {
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
        if (!containsColumn(ge)) {
            result.putAll(ge.getCounters());
            return result;
        }
        // iOS compatibility: null instead of Optional.empty()
        Map<CounterType, Integer> alreadyRemoved = column(ge).get(null);
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

        int totalAdded = totalValues();
        if (totalAdded > 0 && cause != null && cause.hasParam("RememberAmount")) {
            cause.getHostCard().addRemembered(totalAdded);
        }

        result.triggerCountersPutAll(game);
        return !result.isEmpty();
    }
}
