package forge.game.event;

import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.util.TextUtil;

public class GameEventPlayerPriority implements GameEvent {
    private final Player turn;
    private final PhaseType phase;
    private final Player priority;

    public GameEventPlayerPriority(Player turn, PhaseType phase, Player priority) {
        this.turn = turn;
        this.phase = phase;
        this.priority = priority;
    }

    public Player turn() {
        return turn;
    }

    public PhaseType phase() {
        return phase;
    }

    public Player priority() {
        return priority;
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
        return TextUtil.concatWithSpace("Priority -", priority.getName());
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (turn != null ? turn.hashCode() : 0);
        result = 31 * result + (phase != null ? phase.hashCode() : 0);
        result = 31 * result + (priority != null ? priority.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerPriority that = (GameEventPlayerPriority) obj;
        return java.util.Objects.equals(turn, that.turn) &&
               java.util.Objects.equals(phase, that.phase) &&
               java.util.Objects.equals(priority, that.priority);
    }
}
