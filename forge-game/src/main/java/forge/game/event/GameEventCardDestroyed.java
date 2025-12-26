package forge.game.event;

public class GameEventCardDestroyed implements GameEvent {


    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "Card destroyed";
    }
    @Override
    public int hashCode() {
        return 17;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardDestroyed that = (GameEventCardDestroyed) obj;
        return true;
    }
}
