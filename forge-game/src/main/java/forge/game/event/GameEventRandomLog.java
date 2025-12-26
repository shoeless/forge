package forge.game.event;

public class GameEventRandomLog implements GameEvent {
    private final String message;

    public GameEventRandomLog(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (message != null ? message.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventRandomLog that = (GameEventRandomLog) obj;
        return java.util.Objects.equals(message, that.message);
    }

    @Override
    public String toString() {
        return "GameEventRandomLog[message=" + message + "]";
    }
}
