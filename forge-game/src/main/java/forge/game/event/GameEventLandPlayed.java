package forge.game.event;

import forge.game.card.Card;
import forge.game.player.Player;

public class GameEventLandPlayed implements GameEvent {
    private final Player player;
    private final Card land;

    public GameEventLandPlayed(Player player, Card land) {
        this.player = player;
        this.land = land;
    }

    public Player player() {
        return player;
    }

    public Card land() {
        return land;
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
        return "" + player + " played " + land;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + (land != null ? land.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventLandPlayed that = (GameEventLandPlayed) obj;
        return java.util.Objects.equals(player, that.player) &&
               java.util.Objects.equals(land, that.land);
    }
}
