package forge.game.event;

import forge.game.player.Player;
import forge.util.Lang;
import forge.util.TextUtil;

public class GameEventPlayerShardsChanged implements GameEvent {
    private final Player player;
    private final int oldShards;
    private final int newShards;

    public GameEventPlayerShardsChanged(Player player, int oldShards, int newShards) {
        this.player = player;
        this.oldShards = oldShards;
        this.newShards = newShards;
    }

    public Player player() {
        return player;
    }

    public int oldShards() {
        return oldShards;
    }

    public int newShards() {
        return newShards;
    }



    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return TextUtil.concatWithSpace(Lang.getInstance().getPossesive(player.getName()),"shards changed:",  String.valueOf(oldShards),"->", String.valueOf(newShards));
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + oldShards;
        result = 31 * result + newShards;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventPlayerShardsChanged that = (GameEventPlayerShardsChanged) obj;
        return java.util.Objects.equals(player, that.player) &&
               oldShards == that.oldShards &&
               newShards == that.newShards;
    }
}
