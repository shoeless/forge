package forge.game.event;

import forge.game.GameType;
import forge.game.player.Player;
import forge.util.Lang;
import forge.util.TextUtil;

public class GameEventGameStarted implements GameEvent {
    private final GameType gameType;
    private final Player firstTurn;
    private final Iterable<Player> players;

    public GameEventGameStarted(GameType gameType, Player firstTurn, Iterable<Player> players) {
        this.gameType = gameType;
        this.firstTurn = firstTurn;
        this.players = players;
    }

    public GameType gameType() {
        return gameType;
    }

    public Player firstTurn() {
        return firstTurn;
    }

    public Iterable<Player> players() {
        return players;
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
        return TextUtil.concatWithSpace(gameType.toString(),"game between", Lang.joinHomogenous(players), "started.", firstTurn.toString(), "goes first ");
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (gameType != null ? gameType.hashCode() : 0);
        result = 31 * result + (firstTurn != null ? firstTurn.hashCode() : 0);
        result = 31 * result + (players != null ? players.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventGameStarted that = (GameEventGameStarted) obj;
        return java.util.Objects.equals(gameType, that.gameType) &&
               java.util.Objects.equals(firstTurn, that.firstTurn) &&
               java.util.Objects.equals(players, that.players);
    }
}
