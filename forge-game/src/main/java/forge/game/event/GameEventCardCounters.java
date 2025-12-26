package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CounterType;

public class GameEventCardCounters implements GameEvent {
    private final Card card;
    private final CounterType type;
    private final int oldValue;
    private final int newValue;

    public GameEventCardCounters(Card card, CounterType type, int oldValue, int newValue) {
        this.card = card;
        this.type = type;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Card card() {
        return card;
    }

    public CounterType type() {
        return type;
    }

    public int oldValue() {
        return oldValue;
    }

    public int newValue() {
        return newValue;
    }


    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "" + card + " " + type + " counters: " + oldValue + " -> " + newValue;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + oldValue;
        result = 31 * result + newValue;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardCounters that = (GameEventCardCounters) obj;
        return java.util.Objects.equals(card, that.card) &&
               java.util.Objects.equals(type, that.type) &&
               oldValue == that.oldValue &&
               newValue == that.newValue;
    }
}
