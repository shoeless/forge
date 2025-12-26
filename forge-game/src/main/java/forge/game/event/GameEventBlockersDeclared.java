package forge.game.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.util.Lang;
import forge.util.TextUtil;
import forge.util.maps.MapOfLists;

public class GameEventBlockersDeclared implements GameEvent {
    private final Player defendingPlayer;
    private final Map<GameEntity, MapOfLists<Card, Card>> blockers;

    public GameEventBlockersDeclared(Player defendingPlayer, Map<GameEntity, MapOfLists<Card, Card>> blockers) {
        this.defendingPlayer = defendingPlayer;
        this.blockers = blockers;
    }

    public Player defendingPlayer() {
        return defendingPlayer;
    }

    public Map<GameEntity, MapOfLists<Card, Card>> blockers() {
        return blockers;
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
        List<Card> blockerCards = new ArrayList<>();
        for (MapOfLists<Card, Card> vv : blockers.values()) {
            for (Collection<Card> cc : vv.values()) {
                blockerCards.addAll(cc);
            }
        }
        return TextUtil.concatWithSpace(defendingPlayer.getName(),"declared", String.valueOf(blockerCards.size()),"blockers:", Lang.joinHomogenous(blockerCards) );
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (defendingPlayer != null ? defendingPlayer.hashCode() : 0);
        result = 31 * result + (blockers != null ? blockers.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventBlockersDeclared that = (GameEventBlockersDeclared) obj;
        return java.util.Objects.equals(defendingPlayer, that.defendingPlayer) &&
               java.util.Objects.equals(blockers, that.blockers);
    }
}
