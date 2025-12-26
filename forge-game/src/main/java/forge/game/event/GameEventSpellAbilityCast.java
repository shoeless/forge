package forge.game.event;

import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;

public class GameEventSpellAbilityCast implements GameEvent {
    private final SpellAbility sa;
    private final SpellAbilityStackInstance si;
    private final int stackIndex;

    public GameEventSpellAbilityCast(SpellAbility sa, SpellAbilityStackInstance si, int stackIndex) {
        this.sa = sa;
        this.si = si;
        this.stackIndex = stackIndex;
    }

    public SpellAbility sa() {
        return sa;
    }

    public SpellAbilityStackInstance si() {
        return si;
    }

    public int stackIndex() {
        return stackIndex;
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
        return "" + sa.getActivatingPlayer() + (sa.isSpell() ? " cast " : sa.isActivatedAbility() ? " activated " : " triggered ") + sa;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (sa != null ? sa.hashCode() : 0);
        result = 31 * result + (si != null ? si.hashCode() : 0);
        result = 31 * result + stackIndex;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSpellAbilityCast that = (GameEventSpellAbilityCast) obj;
        return java.util.Objects.equals(sa, that.sa) &&
               java.util.Objects.equals(si, that.si) &&
               stackIndex == that.stackIndex;
    }
}
