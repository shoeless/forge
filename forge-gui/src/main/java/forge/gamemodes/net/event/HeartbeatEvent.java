package forge.gamemodes.net.event;

import forge.gamemodes.net.server.RemoteClient;

public class HeartbeatEvent implements NetEvent {
    private static final long serialVersionUID = -8810023718658107789L;

    @Override
    public void updateForClient(final RemoteClient client) {
        // No-op for heartbeats
    }
}
