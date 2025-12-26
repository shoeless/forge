package forge.game.event;

public class GameEventDayTimeChanged implements GameEvent {
    private final boolean daytime;

    public GameEventDayTimeChanged(boolean daytime) {
        this.daytime = daytime;
    }

    public boolean daytime() {
        return daytime;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (daytime ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventDayTimeChanged that = (GameEventDayTimeChanged) obj;
        return daytime == that.daytime;
    }

    @Override
    public String toString() {
        return "GameEventDayTimeChanged[daytime=" + daytime + "]";
    }
}
