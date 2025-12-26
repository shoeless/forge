package forge.game.event;

import forge.game.card.Card;
import forge.game.zone.Zone;
import forge.util.TextUtil;

public class GameEventCardChangeZone implements GameEvent {
    private final Card card;
    private final Zone from;
    private final Zone to;

    public GameEventCardChangeZone(Card card, Zone from, Zone to) {
        this.card = card;
        this.from = from;
        this.to = to;
    }

    public Card card() {
        return card;
    }

    public Zone from() {
        return from;
    }

    public Zone to() {
        return to;
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
        return TextUtil.concatWithSpace("" + card, ":", TextUtil.enclosedBracket("" + from), "->", TextUtil.enclosedBracket("" + to));
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (from != null ? from.hashCode() : 0);
        result = 31 * result + (to != null ? to.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardChangeZone that = (GameEventCardChangeZone) obj;
        return java.util.Objects.equals(card, that.card) &&
               java.util.Objects.equals(from, that.from) &&
               java.util.Objects.equals(to, that.to);
    }
}
