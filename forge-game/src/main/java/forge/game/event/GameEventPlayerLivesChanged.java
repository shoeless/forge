package forge.game.event;

import forge.game.player.Player;
import forge.util.Lang;
import forge.util.TextUtil;

public class GameEventPlayerLivesChanged implements GameEvent {
    private final Player player;
    private final int oldLives;
    private final int newLives;

    public GameEventPlayerLivesChanged(Player player, int oldLives, int newLives) {
        this.player = player;
        this.oldLives = oldLives;
        this.newLives = newLives;
    }

    public Player player() {
        return player;
    }

    public int oldLives() {
        return oldLives;
    }

    public int newLives() {
        return newLives;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return TextUtil.concatWithSpace(Lang.getInstance().getPossesive(player.getName()),"lives changed:",  String.valueOf(oldLives),"->", String.valueOf(newLives));
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + oldLives;
        result = 31 * result + newLives;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerLivesChanged that = (GameEventPlayerLivesChanged) obj;
        return java.util.Objects.equals(player, that.player) &&
               oldLives == that.oldLives &&
               newLives == that.newLives;
    }
}
