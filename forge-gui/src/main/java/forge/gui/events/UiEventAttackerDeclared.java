package forge.gui.events;

import forge.game.GameEntityView;
import forge.game.card.CardView;

public class UiEventAttackerDeclared implements UiEvent {
    private final CardView attacker;
    private final GameEntityView defender;

    public UiEventAttackerDeclared(CardView attacker, GameEntityView defender) {
        this.attacker = attacker;
        this.defender = defender;
    }

    public CardView attacker() {
        return attacker;
    }

    public GameEntityView defender() {
        return defender;
    }



    @Override
    public <T> T visit(final IUiEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return attacker.toString() + ( defender == null ? " removed from combat" : " declared to attack " + defender ); 
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (attacker != null ? attacker.hashCode() : 0);
        result = 31 * result + (defender != null ? defender.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        UiEventAttackerDeclared that = (UiEventAttackerDeclared) obj;
        return java.util.Objects.equals(attacker, that.attacker) &&
               java.util.Objects.equals(defender, that.defender);
    }
}
