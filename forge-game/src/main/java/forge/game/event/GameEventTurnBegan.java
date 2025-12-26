package forge.game.event;

import forge.game.player.Player;
import forge.util.TextUtil;

public class GameEventTurnBegan implements GameEvent {
    private final Player turnOwner;
    private final int turnNumber;

    public GameEventTurnBegan(Player turnOwner, int turnNumber) {
        this.turnOwner = turnOwner;
        this.turnNumber = turnNumber;
    }

    public Player turnOwner() {
        return turnOwner;
    }

    public int turnNumber() {
        return turnNumber;
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
        return TextUtil.concatWithSpace("Turn", String.valueOf(turnNumber), TextUtil.enclosedParen(turnOwner.toString()));
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (turnOwner != null ? turnOwner.hashCode() : 0);
        result = 31 * result + turnNumber;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventTurnBegan that = (GameEventTurnBegan) obj;
        return java.util.Objects.equals(turnOwner, that.turnOwner) &&
               turnNumber == that.turnNumber;
    }
}
