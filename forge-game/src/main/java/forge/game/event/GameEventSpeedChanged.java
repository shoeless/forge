package forge.game.event;

import forge.game.player.Player;

public class GameEventSpeedChanged implements GameEvent {
    private final Player player;
    private final int oldValue;
    private final int newValue;

    public GameEventSpeedChanged(Player player, int oldValue, int newValue) {
        this.player = player;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Player player() {
        return player;
    }

    public int oldValue() {
        return oldValue;
    }

    public int newValue() {
        return newValue;
    }


    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + oldValue;
        result = 31 * result + newValue;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSpeedChanged that = (GameEventSpeedChanged) obj;
        return java.util.Objects.equals(player, that.player) &&
               oldValue == that.oldValue &&
               newValue == that.newValue;
    }

    @Override
    public String toString() {
        return "GameEventSpeedChanged[player=" + player + ", oldValue=" + oldValue + ", newValue=" + newValue + "]";
    }
}
