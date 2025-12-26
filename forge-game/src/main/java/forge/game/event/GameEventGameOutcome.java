package forge.game.event;

import java.util.Collection;

import forge.game.GameOutcome;

public class GameEventGameOutcome implements GameEvent {
    private final GameOutcome result;
    private final Collection<GameOutcome> history;

    public GameEventGameOutcome(GameOutcome result, Collection<GameOutcome> history) {
        this.result = result;
        this.history = history;
    }

    public GameOutcome result() {
        return result;
    }

    public Collection<GameOutcome> history() {
        return history;
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
        return "Game Outcome: " + result.getOutcomeStrings();
    }
    @Override
    public int hashCode() {
        int hash = 17;
        hash = 31 * hash + (result != null ? result.hashCode() : 0);
        hash = 31 * hash + (history != null ? history.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventGameOutcome that = (GameEventGameOutcome) obj;
        return java.util.Objects.equals(result, that.result) &&
               java.util.Objects.equals(history, that.history);
    }
}
