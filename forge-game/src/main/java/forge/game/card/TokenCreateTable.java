package forge.game.card;

import com.google.common.base.Supplier;
import com.google.common.collect.ForwardingTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import forge.game.CardTraitBase;
import forge.game.GameObject;
import forge.game.GameObjectPredicates;
import forge.game.player.Player;
import org.apache.commons.lang3.ObjectUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import forge.util.function.Predicate;

public class TokenCreateTable extends ForwardingTable<Player, Card, Integer> {

    // Insertion-ordered (not HashBasedTable) so cellSet()/rowKeySet()/columnKeySet() iterate deterministically.
    // The iteration order drives TokenEffectBase.makeTokenTable's token-creation order -> nextCardId() ->
    // battlefield insertion order, which AI selection (e.g. CopyPermanentAi's positional first/last-max tie-break)
    // reads. Hash order made multi-token effects (Gruff Triplets, multi-controller copies) create tokens — hence
    // allocate ids — in per-JVM-run order, giving nondeterministic AI copy-target picks on wide token boards (and
    // memo-off != memo-on). Insertion order is deterministic (players x tokenScripts order in createTokenTable)
    // and memo-independent (content-driven, not id-driven).
    Table<Player, Card, Integer> dataMap = Tables.newCustomTable(
            new LinkedHashMap<Player, Map<Card, Integer>>(),
            new Supplier<Map<Card, Integer>>() {
                @Override
                public Map<Card, Integer> get() {
                    return new LinkedHashMap<Card, Integer>();
                }
            });
    
    public TokenCreateTable() {
    }

    @Override
    protected Table<Player, Card, Integer> delegate() {
        return dataMap;
    }

    public int add(Player p, Card c, int i) {
        int old = ObjectUtils.defaultIfNull(this.get(p, c), 0);
        int newValue = old + i;
        this.put(p, c, newValue);
        return newValue;
    }

    public int getFilterAmount(String validOwner, String validToken, final CardTraitBase ctb) {
        final Card host = ctb.getHostCard();
        int result = 0;
        List<Card> filteredCards = null;
        List<Player> filteredPlayer = null;

        if (validOwner == null && validToken == null) {
            for (Integer i : values()) {
                result += i;
            }
            return result;
        }

        if (validOwner != null) {
            Predicate<GameObject> restriction = GameObjectPredicates.restriction(validOwner.split(","), host.getController(), host, ctb);
            filteredPlayer = new ArrayList<>();
            for (Player player : rowKeySet()) {
                if (restriction.test(player)) {
                    filteredPlayer.add(player);
                }
            }
            if (filteredPlayer.isEmpty()) {
                return 0;
            }
        }
        if (validToken != null) {
            filteredCards = CardLists.getValidCardsAsList(columnKeySet(), validToken, host.getController(), host, ctb);
            if (filteredCards.isEmpty()) {
                return 0;
            }
        }

        if (filteredPlayer == null) {
            for (Map.Entry<Card, Map<Player, Integer>> e : columnMap().entrySet()) {
                for (Integer i : e.getValue().values()) {
                    result += i;
                }
            }
            return result;
        }

        if (filteredCards == null) {
            for (Map.Entry<Player, Map<Card, Integer>> e : rowMap().entrySet()) {
                for (Integer i : e.getValue().values()) {
                    result += i;
                }
            }
            return result;
        }

        for (Table.Cell<Player, Card, Integer> c : this.cellSet()) {
            if (!filteredPlayer.contains(c.getRowKey())) {
                continue;
            }
            if (!filteredCards.contains(c.getColumnKey())) {
                continue;
            }
            result += c.getValue();
        }

        return result;
    }
}
