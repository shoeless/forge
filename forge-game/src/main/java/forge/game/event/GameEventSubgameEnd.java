package forge.game.event;

import forge.game.Game;

public class GameEventSubgameEnd implements GameEvent {
    private final Game maingame;
    private final String message;

    public GameEventSubgameEnd(Game maingame, String message) {
        this.maingame = maingame;
        this.message = message;
    }

    public Game maingame() {
        return maingame;
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
        result = 31 * result + (maingame != null ? maingame.hashCode() : 0);
        result = 31 * result + (message != null ? message.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSubgameEnd that = (GameEventSubgameEnd) obj;
        return java.util.Objects.equals(maingame, that.maingame) &&
               java.util.Objects.equals(message, that.message);
    }

    @Override
    public String toString() {
        return "GameEventSubgameEnd[maingame=" + maingame + ", message=" + message + "]";
    }
}
