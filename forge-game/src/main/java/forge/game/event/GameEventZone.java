package forge.game.event;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Lang;
import forge.util.TextUtil;

/**
 * Represents a game event related to a card or ability entering or leaving a zone.
 * Stores information about the affected zone, player, card, and spell ability.
 * Used for tracking zone changes such as casting, moving, or activating cards and abilities.
 */
public class GameEventZone implements GameEvent {
    private final ZoneType zoneType;
    private final Player player;
    private final EventValueChangeType mode;
    private final Card card;
    private final SpellAbility sa;

    public GameEventZone(ZoneType zoneType, Player player, EventValueChangeType mode, Card card, SpellAbility sa) {
        this.zoneType = zoneType;
        this.player = player;
        this.mode = mode;
        this.card = card;
        this.sa = sa;
    }

    public ZoneType zoneType() {
        return zoneType;
    }

    public Player player() {
        return player;
    }

    public EventValueChangeType mode() {
        return mode;
    }

    public Card card() {
        return card;
    }

    public SpellAbility sa() {
        return sa;
    }



    public GameEventZone(ZoneType zoneType, Player player, EventValueChangeType added, Card c) {
        this(zoneType, player, added, c, null);
    }

    public GameEventZone(ZoneType zoneType, SpellAbility sa, EventValueChangeType added) {
        this(zoneType, sa.getActivatingPlayer(), added, sa.getHostCard(), sa);
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
        String owners = player == null ? "Game" : Lang.getInstance().getPossesive(player.getName());
        return card == null && sa == null ?
            TextUtil.concatWithSpace(owners, zoneType.toString(), ":", mode.toString()) :
            TextUtil.concatWithSpace(owners, zoneType.toString(), ":", mode.toString(), "" + (sa == null ? card : sa));
    }
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (zoneType != null ? zoneType.hashCode() : 0);
        result = 31 * result + (player != null ? player.hashCode() : 0);
        result = 31 * result + (mode != null ? mode.hashCode() : 0);
        result = 31 * result + (card != null ? card.hashCode() : 0);
        result = 31 * result + (sa != null ? sa.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameEventZone that = (GameEventZone) obj;
        return java.util.Objects.equals(zoneType, that.zoneType) &&
               java.util.Objects.equals(player, that.player) &&
               java.util.Objects.equals(mode, that.mode) &&
               java.util.Objects.equals(card, that.card) &&
               java.util.Objects.equals(sa, that.sa);
    }
}
