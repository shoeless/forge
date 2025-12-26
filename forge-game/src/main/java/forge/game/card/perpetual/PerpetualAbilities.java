package forge.game.card.perpetual;

import forge.game.card.Card;
import forge.game.card.CardTraitChanges;

public class PerpetualAbilities implements PerpetualInterface {
    private final long timestamp;
    private final CardTraitChanges changes;

    public PerpetualAbilities(long timestamp, CardTraitChanges changes) {
        this.timestamp = timestamp;
        this.changes = changes;
    }

    public long timestamp() {
        return timestamp;
    }

    public CardTraitChanges changes() {
        return changes;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void applyEffect(Card c) {
        c.addChangedCardTraits(changes.copy(c, false), timestamp, (long) 0);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        result = 31 * result + (changes != null ? changes.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PerpetualAbilities that = (PerpetualAbilities) obj;
        return timestamp == that.timestamp &&
               java.util.Objects.equals(changes, that.changes);
    }

    @Override
    public String toString() {
        return "PerpetualAbilities[timestamp=" + timestamp + ", changes=" + changes + "]";
    }

}
