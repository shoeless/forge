package forge.gui.control;

public enum PlaybackSpeed {
    NORMAL(1),
    FAST(0.1),
    FASTER(1.0 / 30),
    FASTEST(1.0 / 50);

    private double modifier = 1;

    PlaybackSpeed(double modifier) {
        this.modifier = modifier;
    }

    public long applyModifier(long milliseconds) {
        return (long) (this.modifier * milliseconds);
    }

    public String nextSpeedText() {
        switch(this) {
            case NORMAL:
                return "10x speed";
            case FAST:
                return "30x speed";
            case FASTER:
                return "50x speed";
            default:
                return "1x speed";
        }
    }

    public PlaybackSpeed nextSpeed() {
        switch(this) {
            case NORMAL:
                return PlaybackSpeed.FAST;
            case FAST:
                return PlaybackSpeed.FASTER;
            case FASTER:
                return PlaybackSpeed.FASTEST;
            default:
                return PlaybackSpeed.NORMAL;
        }
    }
}
