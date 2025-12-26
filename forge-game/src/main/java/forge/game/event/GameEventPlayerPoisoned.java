package forge.game.event;

import forge.game.player.Player;

/** 
 * 
 *
 */
public class GameEventPlayerPoisoned implements GameEvent {
    private final Player receiver;
    private final Player source;
    private final int oldValue;
    private final int amount;

    public GameEventPlayerPoisoned(Player receiver, Player source, int oldValue, int amount) {
        this.receiver = receiver;
        this.source = source;
        this.oldValue = oldValue;
        this.amount = amount;
    }

    public Player receiver() {
        return receiver;
    }

    public Player source() {
        return source;
    }

    public int oldValue() {
        return oldValue;
    }

    public int amount() {
        return amount;
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
        result = 31 * result + oldValue;
        result = 31 * result + amount;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerPoisoned that = (GameEventPlayerPoisoned) obj;
        return java.util.Objects.equals(receiver, that.receiver) &&
               java.util.Objects.equals(source, that.source) &&
               oldValue == that.oldValue &&
               amount == that.amount;
    }

    @Override
    public String toString() {
        return "GameEventPlayerPoisoned[receiver=" + receiver + ", source=" + source + ", oldValue=" + oldValue + ", amount=" + amount + "]";
    }
}
