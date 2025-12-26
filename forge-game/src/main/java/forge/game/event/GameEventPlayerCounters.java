package forge.game.event;

import forge.game.card.CounterType;
import forge.game.player.Player;

public class GameEventPlayerCounters implements GameEvent {
    private final Player receiver;
    private final CounterType type;
    private final int oldValue;
    private final int amount;

    public GameEventPlayerCounters(Player receiver, CounterType type, int oldValue, int amount) {
        this.receiver = receiver;
        this.type = type;
        this.oldValue = oldValue;
        this.amount = amount;
    }

    public Player receiver() {
        return receiver;
    }

    public CounterType type() {
        return type;
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

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "" + receiver + " got " + oldValue + " plus " + amount + " " + type;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (receiver != null ? receiver.hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + oldValue;
        result = 31 * result + amount;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerCounters that = (GameEventPlayerCounters) obj;
        return java.util.Objects.equals(receiver, that.receiver) &&
               java.util.Objects.equals(type, that.type) &&
               oldValue == that.oldValue &&
               amount == that.amount;
    }
}
