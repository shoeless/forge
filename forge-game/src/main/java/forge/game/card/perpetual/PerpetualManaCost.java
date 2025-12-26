package forge.game.card.perpetual;

import forge.card.mana.ManaCost;
import forge.game.card.Card;

public class PerpetualManaCost implements PerpetualInterface {
    private final long timestamp;
    private final ManaCost manaCost;

    public PerpetualManaCost(long timestamp, ManaCost manaCost) {
        this.timestamp = timestamp;
        this.manaCost = manaCost;
    }

    public long timestamp() {
        return timestamp;
    }

    public ManaCost manaCost() {
        return manaCost;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void applyEffect(Card c) {
        c.addChangedManaCost(manaCost, false, timestamp, (long) 0);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        result = 31 * result + (manaCost != null ? manaCost.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PerpetualManaCost that = (PerpetualManaCost) obj;
        return timestamp == that.timestamp &&
               java.util.Objects.equals(manaCost, that.manaCost);
    }

    @Override
    public String toString() {
        return "PerpetualManaCost[timestamp=" + timestamp + ", manaCost=" + manaCost + "]";
    }
}
