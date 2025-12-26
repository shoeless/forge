package forge.game.event;

import forge.game.card.Card;

public class GameEventCardPhased implements GameEvent {
    private final Card card;
    private final boolean phaseState;

    public GameEventCardPhased(Card card, boolean phaseState) {
        this.card = card;
        this.phaseState = phaseState;
    }

    public Card card() {
        return card;
    }

    public boolean phaseState() {
        return phaseState;
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
        return card != null ? card.toString() : "(unknown)" + " changed its phased-out state to " + phaseState; 
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (phaseState ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardPhased that = (GameEventCardPhased) obj;
        return java.util.Objects.equals(card, that.card) &&
               phaseState == that.phaseState;
    }
}
