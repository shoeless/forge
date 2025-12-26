package forge.game.card.perpetual;

import forge.card.ColorSet;
import forge.game.card.Card;

public class PerpetualColors implements PerpetualInterface {
    private final long timestamp;
    private final ColorSet colors;
    private final boolean overwrite;

    public PerpetualColors(long timestamp, ColorSet colors, boolean overwrite) {
        this.timestamp = timestamp;
        this.colors = colors;
        this.overwrite = overwrite;
    }

    public long timestamp() {
        return timestamp;
    }

    public ColorSet colors() {
        return colors;
    }

    public boolean overwrite() {
        return overwrite;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void applyEffect(Card c) {
        c.addColor(colors, !overwrite, timestamp, null);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        result = 31 * result + (colors != null ? colors.hashCode() : 0);
        result = 31 * result + (overwrite ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PerpetualColors that = (PerpetualColors) obj;
        return timestamp == that.timestamp &&
               overwrite == that.overwrite &&
               java.util.Objects.equals(colors, that.colors);
    }

    @Override
    public String toString() {
        return "PerpetualColors[timestamp=" + timestamp + ", colors=" + colors + ", overwrite=" + overwrite + "]";
    }

}
