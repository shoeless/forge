package forge.game.card.perpetual;

import forge.game.card.Card;

public class PerpetualPTBoost implements PerpetualInterface {
    private final long timestamp;
    private final Integer power;
    private final Integer toughness;

    public PerpetualPTBoost(long timestamp, Integer power, Integer toughness) {
        this.timestamp = timestamp;
        this.power = power;
        this.toughness = toughness;
    }

    public long timestamp() {
        return timestamp;
    }

    public Integer power() {
        return power;
    }

    public Integer toughness() {
        return toughness;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void applyEffect(Card c) {
        c.addPTBoost(power, toughness, timestamp, (long) 0);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        result = 31 * result + (power != null ? power.hashCode() : 0);
        result = 31 * result + (toughness != null ? toughness.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PerpetualPTBoost that = (PerpetualPTBoost) obj;
        return timestamp == that.timestamp &&
               java.util.Objects.equals(power, that.power) &&
               java.util.Objects.equals(toughness, that.toughness);
    }

    @Override
    public String toString() {
        return "PerpetualPTBoost[timestamp=" + timestamp + ", power=" + power + ", toughness=" + toughness + "]";
    }
}
