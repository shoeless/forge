package forge.game.event;

import java.util.List;

import forge.game.card.Card;

public class GameEventCombatUpdate implements GameEvent {
    private final List<Card> attackers;
    private final List<Card> blockers;

    public GameEventCombatUpdate(List<Card> attackers, List<Card> blockers) {
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
        GameEventCombatUpdate that = (GameEventCombatUpdate) obj;
        return java.util.Objects.equals(attackers, that.attackers) &&
               java.util.Objects.equals(blockers, that.blockers);
    }

    @Override
    public String toString() {
        return "GameEventCombatUpdate[attackers=" + attackers + ", blockers=" + blockers + "]";
    }
}
