package forge.gui.events;

import forge.game.card.CardView;

public class UiEventBlockerAssigned implements UiEvent {
    private final CardView blocker;
    private final CardView attackerBeingBlocked;

    public UiEventBlockerAssigned(CardView blocker, CardView attackerBeingBlocked) {
        this.blocker = blocker;
        this.attackerBeingBlocked = attackerBeingBlocked;
    }

    public CardView blocker() {
        return blocker;
    }

    public CardView attackerBeingBlocked() {
        return attackerBeingBlocked;
    }



    @Override
    public <T> T visit(final IUiEventVisitor<T> visitor) {
        return visitor.visit(this);
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (blocker != null ? blocker.hashCode() : 0);
        result = 31 * result + (attackerBeingBlocked != null ? attackerBeingBlocked.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        UiEventBlockerAssigned that = (UiEventBlockerAssigned) obj;
        return java.util.Objects.equals(blocker, that.blocker) &&
               java.util.Objects.equals(attackerBeingBlocked, that.attackerBeingBlocked);
    }

    @Override
    public String toString() {
        return "UiEventBlockerAssigned[blocker=" + blocker + ", attackerBeingBlocked=" + attackerBeingBlocked + "]";
    }
}
