package forge.game.event;

import forge.game.player.Player;

public class GameEventCardForetold implements GameEvent {
    private final Player activatingPlayer;

    public GameEventCardForetold(Player activatingPlayer) {
        this.activatingPlayer = activatingPlayer;
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
        return activatingPlayer.getName() + " has foretold.";
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (activatingPlayer != null ? activatingPlayer.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardForetold that = (GameEventCardForetold) obj;
        return java.util.Objects.equals(activatingPlayer, that.activatingPlayer);
    }
}
