package forge.item.generation;

import forge.item.PaperCard;

import java.util.List;
import forge.util.function.Supplier;

/**
 * TODO: Write javadoc for this type.
 *
 */

public interface IUnOpenedProduct extends Supplier<List<PaperCard>> {
    List<PaperCard> get();
}