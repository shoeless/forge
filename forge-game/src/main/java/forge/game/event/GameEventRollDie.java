package forge.game.event;

public class GameEventRollDie implements GameEvent {


    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        return 17;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventRollDie that = (GameEventRollDie) obj;
        return true;
    }

    @Override
    public String toString() {
        return "GameEventRollDie[]";
    }
}
