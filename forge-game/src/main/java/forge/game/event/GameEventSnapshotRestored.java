package forge.game.event;

import forge.util.TextUtil;

public final class GameEventSnapshotRestored implements GameEvent {
    private final boolean start;

    public GameEventSnapshotRestored(boolean start) {
        this.start = start;
    }

    public boolean start() {
        return start;
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        if (start) {
            return TextUtil.concatWithSpace("Undo Snapshot Restoration Started");
        }

        return TextUtil.concatWithSpace("Undo Snapshot Restored");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof GameEventSnapshotRestored)) return false;
        GameEventSnapshotRestored other = (GameEventSnapshotRestored) obj;
        return start == other.start;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(start);
    }
}
