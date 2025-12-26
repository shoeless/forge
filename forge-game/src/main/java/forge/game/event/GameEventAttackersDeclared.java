package forge.game.event;

import com.google.common.collect.Multimap;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;

public class GameEventAttackersDeclared implements GameEvent {
    private final Player player;
    private final Multimap<GameEntity, Card> attackersMap;

    public GameEventAttackersDeclared(Player player, Multimap<GameEntity, Card> attackersMap) {
        this.player = player;
        this.attackersMap = attackersMap;
    }

    public Player player() {
        return player;
    }

    public Multimap<GameEntity, Card> attackersMap() {
        return attackersMap;
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
        return "" + player + " declared attackers: " + attackersMap;
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + (attackersMap != null ? attackersMap.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventAttackersDeclared that = (GameEventAttackersDeclared) obj;
        return java.util.Objects.equals(player, that.player) &&
               java.util.Objects.equals(attackersMap, that.attackersMap);
    }
}
