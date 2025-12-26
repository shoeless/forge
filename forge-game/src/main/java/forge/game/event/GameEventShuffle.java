package forge.game.event;

import forge.game.player.Player;
import forge.util.Lang;
import forge.util.TextUtil;

public class GameEventShuffle implements GameEvent {
    private final Player player;

    public GameEventShuffle(Player player) {
        this.player = player;
    }

    public Player player() {
        return player;
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
        return TextUtil.concatWithSpace(player.toString(), Lang.joinVerb(player.getName(), "shuffle"), "their library");
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventShuffle that = (GameEventShuffle) obj;
        return java.util.Objects.equals(player, that.player);
    }
}
