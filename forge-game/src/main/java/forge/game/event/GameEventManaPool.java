package forge.game.event;

import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.util.Lang;

public class GameEventManaPool implements GameEvent {
    private final Player player;
    private final EventValueChangeType mode;
    private final Mana mana;

    public GameEventManaPool(Player player, EventValueChangeType mode, Mana mana) {
        this.player = player;
        this.mode = mode;
        this.mana = mana;
    }

    public Player player() {
        return player;
    }

    public EventValueChangeType mode() {
        return mode;
    }

    public Mana mana() {
        return mana;
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
        StringBuilder sb = new StringBuilder(Lang.getInstance().getPossessedObject(player.getName(), "mana pool"));
        sb.append(" ").append(mode);
        switch (mode) {
        case Added:
        case Removed:
            sb.append(" - ").append(mana);
            break;
        default:
            break;
        
        }
        return sb.toString();
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + (mode != null ? mode.hashCode() : 0);
        result = 31 * result + (mana != null ? mana.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventManaPool that = (GameEventManaPool) obj;
        return java.util.Objects.equals(player, that.player) &&
               java.util.Objects.equals(mode, that.mode) &&
               java.util.Objects.equals(mana, that.mana);
    }
}
