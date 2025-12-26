package forge.game.event;

import forge.game.player.Player;

public class GameEventScry implements GameEvent {
    private final Player player;
    private final int toTop;
    private final int toBottom;

    public GameEventScry(Player player, int toTop, int toBottom) {
        this.player = player;
        this.toTop = toTop;
        this.toBottom = toBottom;
    }

    public Player player() {
        return player;
    }

    public int toTop() {
        return toTop;
    }

    public int toBottom() {
        return toBottom;
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
        return "" + player + " scried " + toTop + " to top, " + toBottom + " to bottom";
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + toTop;
        result = 31 * result + toBottom;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventScry that = (GameEventScry) obj;
        return java.util.Objects.equals(player, that.player) &&
               toTop == that.toTop &&
               toBottom == that.toBottom;
    }
}
