package forge.game.event;

import forge.game.GameEntity;
import forge.game.card.Card;

public class GameEventCardAttachment implements GameEvent {
    private final Card equipment;
    private final GameEntity oldEntity;
    private final GameEntity newTarget;

    public GameEventCardAttachment(Card equipment, GameEntity oldEntity, GameEntity newTarget) {
        this.equipment = equipment;
        this.oldEntity = oldEntity;
        this.newTarget = newTarget;
    }

    public Card equipment() {
        return equipment;
    }

    public GameEntity oldEntity() {
        return oldEntity;
    }

    public GameEntity newTarget() {
        return newTarget;
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
        return newTarget == null ? "Detached " + equipment + " from " + oldEntity : "Attached " + equipment + (oldEntity == null ? "" : " from " + oldEntity) + " to " + newTarget;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (equipment != null ? equipment.hashCode() : 0);
        result = 31 * result + (oldEntity != null ? oldEntity.hashCode() : 0);
        result = 31 * result + (newTarget != null ? newTarget.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventCardAttachment that = (GameEventCardAttachment) obj;
        return java.util.Objects.equals(equipment, that.equipment) &&
               java.util.Objects.equals(oldEntity, that.oldEntity) &&
               java.util.Objects.equals(newTarget, that.newTarget);
    }
}
