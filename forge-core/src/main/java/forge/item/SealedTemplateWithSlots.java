package forge.item;

import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SealedTemplateWithSlots extends SealedTemplate {
    private final List<BoosterSlot> boosterSlots;

    public SealedTemplateWithSlots(String name0, Iterable<Pair<String, Integer>> itrSlots, List<BoosterSlot> boosterSlots) {
        super(name0, itrSlots);
        this.boosterSlots = boosterSlots;
    }

    public Map<String, BoosterSlot> getNamedSlots() {
        // iOS compatibility: Use traditional loop instead of Stream + Collectors (not available on iOS runtime)
        Map<String, BoosterSlot> result = new HashMap<>();
        for (BoosterSlot slot : boosterSlots) {
            result.put(slot.getSlotName(), slot);
        }
        return result;
    }
}
