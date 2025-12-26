package forge.game.event;

import forge.game.spellability.SpellAbility;

public class GameEventSpellResolved implements GameEvent {
    private final SpellAbility spell;
    private final boolean hasFizzled;

    public GameEventSpellResolved(SpellAbility spell, boolean hasFizzled) {
        this.spell = spell;
        this.hasFizzled = hasFizzled;
    }

    public SpellAbility spell() {
        return spell;
    }

    public boolean hasFizzled() {
        return hasFizzled;
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
        return "Stack resolved " + spell + (hasFizzled ? " (fizzled)" : "");
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (spell != null ? spell.hashCode() : 0);
        result = 31 * result + (hasFizzled ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventSpellResolved that = (GameEventSpellResolved) obj;
        return java.util.Objects.equals(spell, that.spell) &&
               hasFizzled == that.hasFizzled;
    }
}
