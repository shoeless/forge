package forge.game.event;

import forge.game.player.Player;

public class GameEventPlayerRadiation implements GameEvent {
    private final Player receiver;
    private final Player source;
    private final int change;

    public GameEventPlayerRadiation(Player receiver, Player source, int change) {
        this.receiver = receiver;
        this.source = source;
        this.change = change;
    }

    public Player receiver() {
        return receiver;
    }

    public Player source() {
        return source;
    }

    public int change() {
        return change;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (receiver != null ? receiver.hashCode() : 0);
        result = 31 * result + (source != null ? source.hashCode() : 0);
        result = 31 * result + change;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerRadiation that = (GameEventPlayerRadiation) obj;
        return java.util.Objects.equals(receiver, that.receiver) &&
               java.util.Objects.equals(source, that.source) &&
               change == that.change;
    }

    @Override
    public String toString() {
        return "GameEventPlayerRadiation[receiver=" + receiver + ", source=" + source + ", change=" + change + "]";
    }
}
