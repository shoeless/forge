package forge.game.event;

import forge.game.player.Player;

public class GameEventGameRestarted implements GameEvent {
    private final Player whoRestarted;

    public GameEventGameRestarted(Player whoRestarted) {
        this.whoRestarted = whoRestarted;
    }

    public Player whoRestarted() {
        return whoRestarted;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (whoRestarted != null ? whoRestarted.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventGameRestarted that = (GameEventGameRestarted) obj;
        return java.util.Objects.equals(whoRestarted, that.whoRestarted);
    }

    @Override
    public String toString() {
        return "GameEventGameRestarted[whoRestarted=" + whoRestarted + "]";
    }
}
