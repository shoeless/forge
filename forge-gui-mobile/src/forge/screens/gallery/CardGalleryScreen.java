package forge.screens.gallery;

import forge.StaticData;
import forge.itemmanager.CardManager;
import forge.itemmanager.ItemManagerConfig;
import forge.itemmanager.filters.CardArtistFilter;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.util.Localizer;

/**
 * Dedicated card-browsing gallery: a grid of high-resolution card images for every
 * printing, with the usual filters/sort/group plus by-artist support. Long-press a
 * card to open the full-resolution zoomable viewer (CardZoom), shared with the rest
 * of the app. The full card pool is loaded lazily (behind a loading overlay) the
 * first time the screen is shown so the home screen stays responsive.
 */
public class CardGalleryScreen extends FScreen {
    private final CardManager cardManager;
    private boolean initialized;

    public CardGalleryScreen() {
        super(Localizer.getInstance().getMessage("lblCardGallery"));
        //false = show all printings/arts (so each art + edition shows for artist browsing)
        cardManager = add(new CardManager(false));
    }

    @Override
    public void onActivate() {
        super.onActivate();
        if (initialized) {
            return;
        }
        initialized = true;
        //populate off the render path; CARD_GALLERY config opens in image-grid view
        LoadingOverlay.show(Localizer.getInstance().getMessage("lblLoadingCardGallery"), new Runnable() {
            @Override
            public void run() {
                cardManager.setup(ItemManagerConfig.CARD_GALLERY);
                cardManager.addFilter(new CardArtistFilter(cardManager));
                cardManager.setPool(StaticData.instance().getCommonCards().getAllCards());
            }
        });
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        cardManager.setBounds(0, startY, width, height - startY);
    }
}
