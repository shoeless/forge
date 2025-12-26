package forge.game.event;

import java.util.List;

import forge.game.card.Card;

public class GameEventCombatEnded implements GameEvent {
    private final List<Card> attackers;
    private final List<Card> blockers;

    public GameEventCombatEnded(List<Card> attackers, List<Card> blockers) {
        this.attackers = attackers;
        this.blockers = blockers;
    }

    public List<Card> attackers() {
        return attackers;
    }

    public List<Card> blockers() {
        return blockers;
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
        return "Combat ended. Attackers: " + attackers + " Blockers: " + blockers;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (attackers != null ? attackers.hashCode() : 0);
        result = 31 * result + (blockers != null ? blockers.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCombatEnded that = (GameEventCombatEnded) obj;
        return java.util.Objects.equals(attackers, that.attackers) &&
               java.util.Objects.equals(blockers, that.blockers);
    }
}
