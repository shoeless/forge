package forge.game.event;

import forge.game.card.Card;
import forge.game.player.Player;

public class GameEventPlayerDamaged implements GameEvent {
    private final Player target;
    private final Card source;
    private final int amount;
    private final boolean combat;
    private final boolean infect;

    public GameEventPlayerDamaged(Player target, Card source, int amount, boolean combat, boolean infect) {
        this.target = target;
        this.source = source;
        this.amount = amount;
        this.combat = combat;
        this.infect = infect;
    }

    public Player target() {
        return target;
    }

    public Card source() {
        return source;
    }

    public int amount() {
        return amount;
    }

    public boolean combat() {
        return combat;
    }

    public boolean infect() {
        return infect;
    }



    /* (non-Javadoc)
     * @see forge.game.event.GameEvent#visit(forge.game.event.IGameEventVisitor)
     */
    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "" + target + " took " + amount + (infect ? " infect" : combat ? " combat" : "") + " damage from " + source;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (target != null ? target.hashCode() : 0);
        result = 31 * result + (source != null ? source.hashCode() : 0);
        result = 31 * result + amount;
        result = 31 * result + (combat ? 1 : 0);
        result = 31 * result + (infect ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerDamaged that = (GameEventPlayerDamaged) obj;
        return java.util.Objects.equals(target, that.target) &&
               java.util.Objects.equals(source, that.source) &&
               amount == that.amount &&
               combat == that.combat &&
               infect == that.infect;
    }
}
