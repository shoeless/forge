package forge.game.card.perpetual;

import java.util.Set;

import forge.card.CardType;
import forge.card.RemoveType;
import forge.game.card.Card;

public class PerpetualTypes implements PerpetualInterface {
    private final long timestamp;
    private final CardType addTypes;
    private final CardType removeTypes;
    private final Set<RemoveType> removeXTypes;

    public PerpetualTypes(long timestamp, CardType addTypes, CardType removeTypes, Set<RemoveType> removeXTypes) {
        this.timestamp = timestamp;
        this.addTypes = addTypes;
        this.removeTypes = removeTypes;
        this.removeXTypes = removeXTypes;
    }

    public long timestamp() {
        return timestamp;
    }

    public CardType addTypes() {
        return addTypes;
    }

    public CardType removeTypes() {
        return removeTypes;
    }

    public Set<RemoveType> removeXTypes() {
        return removeXTypes;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void applyEffect(Card c) {
        c.addChangedCardTypes(addTypes, removeTypes, false, removeXTypes, timestamp, (long) 0, true, false);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        result = 31 * result + (addTypes != null ? addTypes.hashCode() : 0);
        result = 31 * result + (removeTypes != null ? removeTypes.hashCode() : 0);
        result = 31 * result + (removeXTypes != null ? removeXTypes.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PerpetualTypes that = (PerpetualTypes) obj;
        return timestamp == that.timestamp &&
               java.util.Objects.equals(addTypes, that.addTypes) &&
               java.util.Objects.equals(removeTypes, that.removeTypes) &&
               java.util.Objects.equals(removeXTypes, that.removeXTypes);
    }

    @Override
    public String toString() {
        return "PerpetualTypes[timestamp=" + timestamp + ", addTypes=" + addTypes +
               ", removeTypes=" + removeTypes + ", removeXTypes=" + removeXTypes + "]";
    }

}
