package forge.game.event;

import forge.game.card.Card;

public class GameEventCardTapped implements GameEvent {
    private final Card card;
    private final boolean tapped;

    public GameEventCardTapped(Card card, boolean tapped) {
        this.card = card;
        this.tapped = tapped;
    }

    public Card card() {
        return card;
    }

    public boolean tapped() {
        return tapped;
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
        return "" + card.getController() + (tapped ? " tapped " : " untapped ") + card;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (tapped ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardTapped that = (GameEventCardTapped) obj;
        return java.util.Objects.equals(card, that.card) &&
               tapped == that.tapped;
    }
}
