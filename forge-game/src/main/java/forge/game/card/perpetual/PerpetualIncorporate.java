package forge.game.card.perpetual;

import forge.card.ColorSet;
import forge.card.mana.ManaCost;
import forge.game.card.Card;

public class PerpetualIncorporate implements PerpetualInterface {
    private final long timestamp;
    private final ManaCost incorporate;

    public PerpetualIncorporate(long timestamp, ManaCost incorporate) {
        this.timestamp = timestamp;
        this.incorporate = incorporate;
    }

    public long timestamp() {
        return timestamp;
    }

    public ManaCost incorporate() {
        return incorporate;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void applyEffect(Card c) {
        ColorSet colors = ColorSet.fromMask(incorporate.getColorProfile());
        c.addChangedManaCost(incorporate, true, timestamp, (long) 0);
        c.addColorByText(colors, true, timestamp, null);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        result = 31 * result + (incorporate != null ? incorporate.hashCode() : 0);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PerpetualIncorporate that = (PerpetualIncorporate) obj;
        return timestamp == that.timestamp &&
               java.util.Objects.equals(incorporate, that.incorporate);
    }

    @Override
    public String toString() {
        return "PerpetualIncorporate[timestamp=" + timestamp + ", incorporate=" + incorporate + "]";
    }
}
