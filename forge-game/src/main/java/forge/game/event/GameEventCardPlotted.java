package forge.game.event;

import forge.game.card.Card;
import forge.game.player.Player;

public class GameEventCardPlotted implements GameEvent {
    private final Card card;
    private final Player activatingPlayer;

    public GameEventCardPlotted(Card card, Player activatingPlayer) {
        this.card = card;
        this.activatingPlayer = activatingPlayer;
    }

    public Card card() {
        return card;
    }

    public Player activatingPlayer() {
        return activatingPlayer;
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
        return activatingPlayer.getName() + " has plotted " + (card != null ? card.toString() : "(unknown)");
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (activatingPlayer != null ? activatingPlayer.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardPlotted that = (GameEventCardPlotted) obj;
        return java.util.Objects.equals(card, that.card) &&
               java.util.Objects.equals(activatingPlayer, that.activatingPlayer);
    }
}
