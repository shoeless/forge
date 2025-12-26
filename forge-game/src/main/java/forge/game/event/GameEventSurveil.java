package forge.game.event;

import forge.game.player.Player;

public class GameEventSurveil implements GameEvent {
    private final Player player;
    private final int toLibrary;
    private final int toGraveyard;

    public GameEventSurveil(Player player, int toLibrary, int toGraveyard) {
        this.player = player;
        this.toLibrary = toLibrary;
        this.toGraveyard = toGraveyard;
    }

    public Player player() {
        return player;
    }

    public int toLibrary() {
        return toLibrary;
    }

    public int toGraveyard() {
        return toGraveyard;
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
        return "" + player + " surveilled " + toLibrary + " to library, " + toGraveyard + " to graveyard";
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + toLibrary;
        result = 31 * result + toGraveyard;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSurveil that = (GameEventSurveil) obj;
        return java.util.Objects.equals(player, that.player) &&
               toLibrary == that.toLibrary &&
               toGraveyard == that.toGraveyard;
    }
}
