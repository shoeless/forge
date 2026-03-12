package forge.player;

import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Set;

public class PlayerZoneUpdate implements Serializable {
    private static final long serialVersionUID = -7666875897455073969L;

    private final PlayerView player;
    private final Set<ZoneType> zones;

    public PlayerZoneUpdate(final PlayerView player, final ZoneType zone) {
        if (player == null ) {
            throw new NullPointerException();
        }
        this.player = player;
        if (zone != null) {
            this.zones = EnumSet.of(zone);
        } else {
            this.zones = EnumSet.noneOf(ZoneType.class);
        }
    }

    /** Creates a PlayerZoneUpdate with a pre-built set of zones. */
    public PlayerZoneUpdate(final PlayerView player, final Set<ZoneType> zones) {
        if (player == null) {
            throw new NullPointerException();
        }
        this.player = player;
        this.zones = zones.isEmpty() ? EnumSet.noneOf(ZoneType.class) : EnumSet.copyOf(zones);
    }

    public PlayerView getPlayer() {
        return player;
    }
    public Set<ZoneType> getZones() {
        return zones;
    }

    void addZone(final ZoneType zone) {
        if (zone == null) {
            return;
        }
        zones.add(zone);
    }
    void add(final PlayerZoneUpdate other) {
        if (other == null) {
            return;
        }
        zones.addAll(other.getZones());
    }
}
