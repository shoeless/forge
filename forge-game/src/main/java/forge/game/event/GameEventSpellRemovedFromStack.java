package forge.game.event;

import forge.game.spellability.SpellAbility;

public class GameEventSpellRemovedFromStack implements GameEvent {
    private final SpellAbility sa;

    public GameEventSpellRemovedFromStack(SpellAbility sa) {
        this.sa = sa;
    }

    public SpellAbility sa() {
        return sa;
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
        return "Stack removed " + sa;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (sa != null ? sa.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSpellRemovedFromStack that = (GameEventSpellRemovedFromStack) obj;
        return java.util.Objects.equals(sa, that.sa);
    }
}
