package forge.game.event;

import java.util.Arrays;
import java.util.Collection;

import com.google.common.collect.Iterables;

import forge.game.card.Card;
import forge.util.IterableUtil;

/**
 * This means card's characteristics have changed on server, clients must re-request them
 */
public class GameEventCardStatsChanged implements GameEvent {
    private final Collection<Card> cards;
    private final boolean transform;

    public GameEventCardStatsChanged(Collection<Card> cards, boolean transform) {
        this.cards = cards;
        this.transform = transform;
    }

    public Collection<Card> cards() {
        return cards;
    }

    public boolean transform() {
        return transform;
    }



    public GameEventCardStatsChanged(Card affected) {
        this(affected, false);
    }

    public GameEventCardStatsChanged(Card affected, boolean isTransform) {
        this(Arrays.asList(affected), false);
        //the transform should only fire once so the flip effect sound will trigger once every transformation...
        // disable for now
    }

    public GameEventCardStatsChanged(Collection<Card> affected) {
        this(affected, false);
    }

    /* (non-Javadoc)
     * @see forge.game.event.GameEvent#visit(forge.game.event.IGameEventVisitor)
     */
    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        Card card = Iterables.getFirst(cards, null);
        if (null == card)
            return "Card state changes: (empty list)";
        // iOS compatibility: Use IterableUtil.join() instead of StringUtils.join() which uses Stream API
        if (cards.size() == 1)
            return "Card state changes: " + card.getName() +
                  " (" + IterableUtil.join(" ", card.getType()) + ") " +
                  card.getNetPower() + "/" + card.getNetToughness();
        else
            return "Card state changes: " + card.getName() +
                  " (" + IterableUtil.join(" ", card.getType()) + ") " +
                  card.getNetPower() + "/" + card.getNetToughness() +
                  " and " + (cards.size() - 1) + " more";
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (cards != null ? cards.hashCode() : 0);
        result = 31 * result + (transform ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardStatsChanged that = (GameEventCardStatsChanged) obj;
        return java.util.Objects.equals(cards, that.cards) &&
               transform == that.transform;
    }
}
