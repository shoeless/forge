package forge.game.event;

import forge.game.card.Card;

public class GameEventCardDamaged implements GameEvent {
    private final Card card;
    private final Card source;
    private final int amount;
    private final DamageType type;

    public GameEventCardDamaged(Card card, Card source, int amount, DamageType type) {
        this.card = card;
        this.source = source;
        this.amount = amount;
        this.type = type;
    }

    public Card card() {
        return card;
    }

    public Card source() {
        return source;
    }

    public int amount() {
        return amount;
    }

    public DamageType type() {
        return type;
    }



    public enum DamageType {
        Normal, 
        M1M1Counters, 
        Deathtouch, 
        LoyaltyLoss
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
        return "" + source + " dealt " + amount + " " + type + " damage to " + card;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (source != null ? source.hashCode() : 0);
        result = 31 * result + amount;
        result = 31 * result + (type != null ? type.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardDamaged that = (GameEventCardDamaged) obj;
        return java.util.Objects.equals(card, that.card) &&
               java.util.Objects.equals(source, that.source) &&
               amount == that.amount &&
               java.util.Objects.equals(type, that.type);
    }
}
