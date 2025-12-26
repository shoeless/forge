package forge.game.event;

import java.util.Arrays;
import java.util.Collection;

import forge.game.player.Player;
import forge.util.Lang;
import forge.util.TextUtil;

/**
 * This means card's characteristics have changed on server, clients must re-request them
 */
public class GameEventPlayerStatsChanged implements GameEvent {
    private final Collection<Player> players;
    private final boolean updateCards;

    public GameEventPlayerStatsChanged(Collection<Player> players, boolean updateCards) {
        this.players = players;
        this.updateCards = updateCards;
    }

    public Collection<Player> players() {
        return players;
    }

    public boolean updateCards() {
        return updateCards;
    }



    public GameEventPlayerStatsChanged(Player affected, boolean updateCards) {
        this(Arrays.asList(affected), updateCards);
    }

    /* (non-Javadoc)
     * @see forge.game.event.GameEvent#visit(forge.game.event.IGameEventVisitor)
     */
    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        if (null == players || players.isEmpty()) {
            return "Player state changes: (empty list)";
        }
        return TextUtil.concatWithSpace("Player state changes:", Lang.joinHomogenous(players));
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (players != null ? players.hashCode() : 0);
        result = 31 * result + (updateCards ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerStatsChanged that = (GameEventPlayerStatsChanged) obj;
        return java.util.Objects.equals(players, that.players) &&
               updateCards == that.updateCards;
    }
}
