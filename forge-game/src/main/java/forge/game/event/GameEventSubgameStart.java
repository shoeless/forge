package forge.game.event;

import forge.game.Game;

public class GameEventSubgameStart implements GameEvent {
    private final Game subgame;
    private final String message;

    public GameEventSubgameStart(Game subgame, String message) {
        this.subgame = subgame;
        this.message = message;
    }

    public Game subgame() {
        return subgame;
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
        result = 31 * result + (subgame != null ? subgame.hashCode() : 0);
        result = 31 * result + (message != null ? message.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSubgameStart that = (GameEventSubgameStart) obj;
        return java.util.Objects.equals(subgame, that.subgame) &&
               java.util.Objects.equals(message, that.message);
    }

    @Override
    public String toString() {
        return "GameEventSubgameStart[subgame=" + subgame + ", message=" + message + "]";
    }
}
