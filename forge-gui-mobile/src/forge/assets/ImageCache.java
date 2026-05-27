/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.assets;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

import com.badlogic.gdx.graphics.Pixmap;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import forge.deck.DeckProxy;
import forge.gui.GuiBase;
import forge.item.PaperToken;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import forge.Forge;
import forge.ImageKeys;
import forge.card.CardEdition;
import forge.card.CardRenderer;
import forge.deck.Deck;
import forge.game.card.CardView;
import forge.game.player.IHasIcon;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.util.ImageUtil;

/**
 * This class stores ALL card images in a cache with soft values. this means
 * that the images may be collected when they are not needed any more, but will
 * be kept as long as possible.
 * <p/>
 * The keys are the following:
 * <ul>
 * <li>Keys start with the file name, extension is skipped</li>
 * <li>The key without suffix belongs to the unmodified image from the file</li>
 * </ul>
 *
 * @author Forge
 * @version $Id: ImageCache.java 24769 2014-02-09 13:56:04Z Hellfish $
 */
public class ImageCache {
    private static ImageCache imageCache;
    private Supplier<HashSet<String>> missingIconKeys = Suppliers.memoize(HashSet::new);

    // iOS fix: Keep Pixmaps alive - iOS Texture requires Pixmap to stay in memory
    // Don't dispose Pixmaps - let GC handle them when memory is low
    private static final java.util.HashMap<Texture, Pixmap> pixmapCache = new java.util.HashMap<Texture, Pixmap>();

    // iOS fix: Cache for downloaded image textures (bypassing AssetManager)
    private static final java.util.LinkedHashMap<String, Texture> downloadedTextureCache =
            new java.util.LinkedHashMap<String, Texture>(120, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Texture> eldest) {
                    if (size() > 120) {
                        Texture t = eldest.getValue();
                        pixmapCache.remove(t);
                        textureToPath.remove(t);
                        try { t.dispose(); } catch (Exception ignored) {}
                        return true;
                    }
                    return false;
                }
            };

    // iOS fix: Reverse mapping from Texture to its file path for ImageRecord lookups
    // Needed because texture.toString() isn't unique (returns dimensions, not path)
    private static final java.util.IdentityHashMap<Texture, String> textureToPath = new java.util.IdentityHashMap<Texture, String>();
    public int counter = 0;
    private int maxCardCapacity = 300; //default card capacity
    private EvictingQueue<String> q;
    private Set<String> cardsLoaded;
    private Queue<String> syncQ;
    public static ImageCache getInstance() {
        if (imageCache == null) {
            imageCache = new ImageCache();
            imageCache.initCache(Forge.cacheSize);
        }
        return imageCache;
    }
    private void initCache(int capacity) {
        //override maxCardCapacity
        maxCardCapacity = capacity;
        //init q
        q = EvictingQueue.create(capacity);
        //init syncQ for threadsafe use
        syncQ = Queues.synchronizedQueue(q);
        //cap
        int cl = GuiBase.isAndroid() ? maxCardCapacity + (capacity / 3) : 400;
        cardsLoaded = new HashSet<>(cl);
    }

    private Set<String> getCardsLoaded() {
        if (cardsLoaded == null) {
            cardsLoaded = new HashSet<>(400);
        }
        return cardsLoaded;
    }

    private EvictingQueue<String> getQ() {
        if (q == null) {
            q = EvictingQueue.create(400);
        }
        return q;
    }

    private Queue<String> getSyncQ() {
        if (syncQ == null)
            syncQ = Queues.synchronizedQueue(getQ());
        return syncQ;
    }

    /**
     * iOS fix: Convert absolute path to relative path for libGDX compatibility.
     * libGDX's AssetManager on iOS requires relative paths from the local directory.
     * On iOS, files are in Documents/ but libGDX local base is Library/local/,
     * so we need to construct a relative path like ../../Documents/...
     */
    private String toRelativePath(String absolutePath) {
        // Extract the portion after the app container UUID
        // Path format: .../Application/UUID/Documents/cache/pics/...
        // We want: ../../Documents/cache/pics/...

        int documentsIndex = absolutePath.indexOf("/Documents/");
        if (documentsIndex != -1) {
            // Found Documents directory - construct relative path from Library/local
            String relativePath = "../../Documents" + absolutePath.substring(documentsIndex + "/Documents".length());
            return relativePath;
        }

        int libraryIndex = absolutePath.indexOf("/Library/");
        if (libraryIndex != -1) {
            // File is in Library somewhere - might already be accessible
            String relativePath = absolutePath.substring(absolutePath.indexOf("/Library/") + "/Library/".length());
            return relativePath;
        }

        return absolutePath;
    }

    public Texture getDefaultImage() {
        return Forge.getAssets().getDefaultImage();
    }

    private Supplier<HashMap<String, ImageRecord>> imageRecord = Suppliers.memoize(() -> new HashMap<>(maxCardCapacity + (maxCardCapacity / 3)));
    private boolean imageLoaded, delayLoadRequested;

    public void allowSingleLoad() {
        imageLoaded = false; //reset at the beginning of each render
        delayLoadRequested = false;
    }

    public void clear() {
        missingIconKeys.get().clear();
        ImageKeys.clearMissingCards();
    }

    public void disposeTextures() {
        CardRenderer.clearcardArtCache();
        //unload all cardsLoaded
        try {
            for (String fileName : getCardsLoaded()) {
                if (Forge.getAssets().manager().get(fileName, Texture.class, false) != null) {
                    Forge.getAssets().manager().unload(fileName);
                }
            }
        } catch (Exception ignored) {}
        getCardsLoaded().clear();

        // Clear iOS-specific texture caches to prevent memory accumulation
        // across games. Textures will be reloaded on demand when needed.
        for (Texture t : downloadedTextureCache.values()) {
            try { t.dispose(); } catch (Exception ignored) {}
        }
        downloadedTextureCache.clear();
        pixmapCache.clear();
        textureToPath.clear();
        imageRecord.get().clear();
        counter = 0;

        ((Forge) Gdx.app.getApplicationListener()).needsUpdate = true;
    }

    /**
     * Update counter for use with adventure mode since it uses direct loading for assetmanager for loot and shops
     */
    public void updateSynqCount(File file, int count) {
        if (file == null)
            return;
        getSyncQ().add(file.getPath());
        getCardsLoaded().add(file.getPath());
        counter += count;
    }

    public Texture getImage(InventoryItem ii) {
        boolean useDefault = ii instanceof DeckProxy;
        String imageKey = ii.getImageKey(false);
        if (imageKey != null) {
            if (imageKey.startsWith(ImageKeys.CARD_PREFIX) || imageKey.startsWith(ImageKeys.TOKEN_PREFIX))
                return getImage(ii.getImageKey(false), useDefault, false);
        }
        return getImage(ii.getImageKey(false), true, true);
    }

    /**
     * retrieve an icon from the cache.  returns the current skin's ICO_UNKNOWN if the icon image is not found
     * in the cache and cannot be loaded from disk.
     */
    public FImage getIcon(IHasIcon ihi) {
        String imageKey = ihi.getIconImageKey();
        final Texture icon;
        if (missingIconKeys.get().contains(imageKey) || (icon = getImage(ihi.getIconImageKey(), false, true)) == null) {
            missingIconKeys.get().add(imageKey);
            return FSkinImage.UNKNOWN;
        }
        return new FTextureImage(icon);
    }

    /**
     * checks the card image exists from the disk.
     */
    public boolean imageKeyFileExists(String imageKey) {
        if (StringUtils.isEmpty(imageKey))
            return false;

        if (imageKey.length() < 2)
            return false;

        final String prefix = imageKey.substring(0, 2);

        PaperCard paperCard;
        if (prefix.equals(ImageKeys.CARD_PREFIX)) {
            try {
                paperCard = ImageUtil.getPaperCardFromImageKey(imageKey);
            } catch (Exception e) {
                return false;
            }
            if (paperCard == null)
                return false;

            if (!FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER)) {
                return paperCard.hasImage();
            } else {
                final boolean backFace = imageKey.endsWith(ImageKeys.BACKFACE_POSTFIX);
                final String cardfilename = backFace ? paperCard.getCardAltImageKey() : paperCard.getCardImageKey();
                return ImageKeys.getCachedCardsFile(cardfilename) != null;
            }
        } else if (prefix.equals(ImageKeys.TOKEN_PREFIX)) {
            final String tokenfilename = imageKey.substring(2) + ".jpg";
            File tokenFile = new File(ForgeConstants.CACHE_TOKEN_PICS_DIR, tokenfilename);
            return tokenFile.exists();
        }

        return true;
    }

    /**
     * This requests the original unscaled image from the cache for the given key.
     * If the image does not exist then it can return a default image if desired.
     * <p>
     * If the requested image is not present in the cache then it attempts to load
     * the image from file (slower) and then add it to the cache for fast future access.
     * </p>
     */
    public Texture getImage(String imageKey, boolean useDefaultIfNotFound) {
        return getImage(imageKey, useDefaultIfNotFound, false);
    }

    public Texture getImage(String imageKey, boolean useDefaultIfNotFound, boolean others) {
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_DISABLE_CARD_IMAGES))
            return null;

        if (StringUtils.isEmpty(imageKey)) {
            return null;
        }

        boolean altState = imageKey.endsWith(ImageKeys.BACKFACE_POSTFIX);
        if (altState) {
            imageKey = imageKey.substring(0, imageKey.length() - ImageKeys.BACKFACE_POSTFIX.length());
        }
        if (imageKey.startsWith(ImageKeys.CARD_PREFIX)) {
            PaperCard card = ImageUtil.getPaperCardFromImageKey(imageKey);
            if (card != null)
                imageKey = altState ? card.getCardAltImageKey() : card.getCardImageKey();
        } else if (imageKey.startsWith(ImageKeys.TOKEN_PREFIX)) {
            PaperToken token = ImageUtil.getPaperTokenFromImageKey(imageKey);
            if (token != null)
                imageKey = token.getCardImageKey();
        }

        if (StringUtils.isBlank(imageKey)) {
            if (useDefaultIfNotFound)
                return getDefaultImage();
            else
                return null;
        }

        Texture image;
        File imageFile = ImageKeys.getImageFile(imageKey);
        if (useDefaultIfNotFound) {
            // Load from file and add to cache if not found in cache initially.
            image = getAsset(imageFile);

            if (image != null) {
                return image;
            }

            if (imageLoaded) { //prevent loading more than one image each render for performance
                if (!delayLoadRequested) {
                    //ensure images continue to load even if no input is being received
                    delayLoadRequested = true;
                    Gdx.graphics.requestRendering();
                }
                return null;
            }
            imageLoaded = true;
        }

        try {
            image = loadAsset(imageKey, imageFile, others);
        } catch (final Exception ex) {
            image = null;
        }

        // No image file exists for the given key so optionally associate with
        // a default "not available" image.
        if (image == null) {
            if (useDefaultIfNotFound) {
                image = getDefaultImage();
                /*fix not loading image file since we intentionally not to update the cache in order for the
                  image fetcher to update automatically after the card image/s are downloaded*/
                imageLoaded = false;
                if (image != null && imageRecord.get().get(image.toString()) == null)
                    imageRecord.get().put(image.toString(), new ImageRecord(Color.valueOf("#171717").toString(), false, getRadius(image), image.toString().contains(".fullborder.") || image.toString().contains("tokens"))); //black border
            }
        }
        return image;
    }

    private Texture getAsset(File file) {
        if (file == null)
            return null;

        String absolutePath = file.getPath();
        String path = toRelativePath(absolutePath);

        // iOS fix: Check downloaded texture cache first (for images in Documents)
        if (absolutePath.contains("/Documents/cache")) {
            Texture cached = downloadedTextureCache.get(absolutePath);
            if (cached != null) {
                return cached;
            }
        }

        return Forge.getAssets().manager().get(path, Texture.class, false);
    }

    private Texture loadAsset(String imageKey, File file, boolean others) {
        if (file == null)
            return null;
        Texture check = getAsset(file);
        if (check != null)
            return check;

        // iOS fix: Convert absolute path to relative path for AssetManager
        String absolutePath = file.getPath();
        String fileName = toRelativePath(absolutePath);

        // iOS fix: For downloaded images (in Documents/cache), bypass AssetManager
        // Read file as bytes and create Pixmap directly (libGDX iOS can't load from FileHandle in Documents)
        boolean isDownloadedImage = absolutePath.contains("/Documents/cache");
        Texture directTexture = null;

        if (isDownloadedImage) {
            try {
                java.io.File imageFile = new java.io.File(absolutePath);
                if (imageFile.exists()) {
                    // Read file as byte array
                    byte[] imageBytes = new byte[(int) imageFile.length()];
                    java.io.FileInputStream fis = new java.io.FileInputStream(imageFile);
                    fis.read(imageBytes);
                    fis.close();

                    // Create Pixmap from bytes - use RGBA8888 to preserve color channels correctly
                    Pixmap pixmap = new Pixmap(imageBytes, 0, imageBytes.length);

                    // Create Texture from Pixmap (no mipmaps for faster upload)
                    directTexture = new Texture(pixmap, false);
                    // Apply Linear filtering for smoother card images on high-DPI/Retina displays
                    directTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

                    // iOS fix: Store Pixmap - iOS Texture needs Pixmap to stay alive
                    // Rely on GC to clean up when memory is low
                    pixmapCache.put(directTexture, pixmap);

                    // iOS fix: Cache the texture so we don't reload the same image!
                    downloadedTextureCache.put(absolutePath, directTexture);
                }
            } catch (Exception e) {
                System.err.println("Failed to load downloaded image: " + absolutePath + " - " + e.getMessage());
            }
        }

        if (!others) {
            //update first before clearing
            getSyncQ().add(fileName);
            getCardsLoaded().add(fileName);
            unloadCardTextures(false);
        }

        // Only use AssetManager for bundled assets (not downloaded images)
        if (!isDownloadedImage) {
            //load to assetmanager
            try {
                if (Forge.getAssets().manager().get(fileName, Texture.class, false) == null) {
                    Forge.getAssets().manager().load(fileName, Texture.class, Forge.getAssets().getTextureFilter());
                    Forge.getAssets().manager().finishLoadingAsset(fileName);
                    counter += 1;
                }
            } catch (Exception e) {
                System.err.println("Failed to load image: " + fileName);
                System.err.println("Error details: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }

        //return loaded assets
        if (others) {
            return directTexture != null ? directTexture : Forge.getAssets().manager().get(fileName, Texture.class, false);
        } else {
            Texture cardTexture = directTexture != null ? directTexture : Forge.getAssets().manager().get(fileName, Texture.class, false);
            //if full bordermasking is enabled, update the border color
            if (cardTexture != null) {
                String setCode = imageKey.split("/")[0].trim().toUpperCase();
                int radius;
                if (setCode.equals("A") || setCode.equals("LEA") || setCode.equals("B") || setCode.equals("LEB"))
                    radius = 28;
                else if (setCode.equals("MED") || setCode.equals("ME2") || setCode.equals("ME3") || setCode.equals("ME4") || setCode.equals("TD0") || setCode.equals("TD1"))
                    radius = 25;
                else
                    radius = 22;
                // Downloaded images from Scryfall (in Documents/cache) are always fullborder
                // Also check file path for .fullborder. or tokens
                boolean isFullBorder = isDownloadedImage || absolutePath.contains(".fullborder.") || absolutePath.contains("tokens");
                // Store texture-to-path mapping for later lookups
                textureToPath.put(cardTexture, absolutePath);
                // Use absolutePath as key instead of texture.toString() since toString() isn't unique per image
                updateImageRecord(absolutePath, isCloserToWhite(getpixelColor(cardTexture)), radius, isFullBorder);
            }
            return cardTexture;
        }
    }

    public void unloadCardTextures(boolean removeAll) {
        if (removeAll) {
            try {
                for (String asset : Forge.getAssets().manager().getAssetNames()) {
                    if (asset.contains(".full")) {
                        Forge.getAssets().manager().unload(asset);
                    }
                }
                getSyncQ().clear();
                getCardsLoaded().clear();
                counter = 0;
                CardRenderer.clearcardArtCache();
            } catch (Exception ignored) {}
            return;
        }
        if (getCardsLoaded().size() <= maxCardCapacity)
            return;
        //get latest images from syncQ
        Set<String> newQ = Sets.newHashSet(getSyncQ());
        //get all images not in newQ (cards to unload)
        Set<String> toUnload = Sets.difference(getCardsLoaded(), newQ);
        //unload from assetmanager to save RAM
        try {
            for (String asset : toUnload) {
                if (Forge.getAssets().manager().get(asset, Texture.class, false) != null) {
                    Forge.getAssets().manager().unload(asset);
                }
                getCardsLoaded().remove(asset);
            }
            //clear cachedArt since this is dependant to the loaded texture
            CardRenderer.clearcardArtCache();
            ((Forge) Gdx.app.getApplicationListener()).needsUpdate = true;
        } catch (Exception ignored) {}
    }

    public void preloadCache(Iterable<String> keys) {
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_DISABLE_CARD_IMAGES))
            return;
        for (String imageKey : keys) {
            if (getImage(imageKey, false) == null)
                System.err.println("could not load card image:" + imageKey);
        }
    }

    public void preloadCache(Deck deck) {
        if (FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_DISABLE_CARD_IMAGES))
            return;
        if (deck == null)
            return;
        if (deck.getAllCardsInASinglePool().toFlatList().size() <= 100) {
            for (PaperCard p : deck.getAllCardsInASinglePool().toFlatList()) {
                if (getImage(p.getImageKey(false), false) == null)
                    System.err.println("could not load card image:" + p);
            }
        }
    }

    public TextureRegion croppedBorderImage(Texture image) {
        String key = getTextureKey(image);
        if (key == null || !key.contains(".fullborder."))
            return new TextureRegion(image);
        float rscale = 0.96f;
        int rw = Math.round(image.getWidth() * rscale);
        int rh = Math.round(image.getHeight() * rscale);
        int rx = Math.round((image.getWidth() - rw) / 2f);
        int ry = Math.round((image.getHeight() - rh) / 2f) - 2;
        return new TextureRegion(image, rx, ry, rw, rh);
    }

    public Color borderColor(Texture t) {
        if (t == null)
            return Color.valueOf("#171717");
        try {
            return Color.valueOf(imageRecord.get().get(getTextureKey(t)).colorValue);
        } catch (Exception e) {
            return Color.valueOf("#171717");
        }
    }

    public int getFSkinBorders(CardView c) {
        if (c == null)
            return 0;

        CardView.CardStateView state = c.getCurrentState();
        CardEdition ed = FModel.getMagicDb().getEditions().get(state.getSetCode());
        // TODO: Treatment for silver here
        if (ed != null && ed.getBorderColor() == CardEdition.BorderColor.WHITE && state.getFoilIndex() == 0)
            return 1;
        return 0;
    }
    public void updateImageRecord(String textureString, Pair<String, Boolean> data, int radius, boolean fullborder) {
        imageRecord.get().put(textureString, new ImageRecord(data.getLeft(), data.getRight(), radius, fullborder));
    }

    // Helper to get the path key for a texture (uses textureToPath mapping or falls back to toString)
    private String getTextureKey(Texture t) {
        if (t == null) return null;
        String path = textureToPath.get(t);
        return path != null ? path : t.toString();
    }

    public int getRadius(Texture t) {
        if (t == null)
            return 20;
        ImageRecord record = imageRecord.get().get(getTextureKey(t));
        if (record == null)
            return 20;
        Integer i = record.cardRadius;
        if (i == null)
            return 20;
        return i;
    }

    public boolean isFullBorder(Texture image) {
        if (image == null)
            return false;
        ImageRecord record = imageRecord.get().get(getTextureKey(image));
        if (record == null)
            return false;
        return record.isFullBorder;
    }

    public FImage getBorder(String textureString) {
        ImageRecord record = imageRecord.get().get(textureString);
        if (record == null)
            return FSkinImage.IMG_BORDER_BLACK;
        Boolean border = record.isCloserToWhite;
        if (border == null)
            return FSkinImage.IMG_BORDER_BLACK;
        return border ? FSkinImage.IMG_BORDER_WHITE : FSkinImage.IMG_BORDER_BLACK;
    }

    public FImage getBorderImage(String textureString, boolean canshow) {
        if (!canshow)
            return FSkinImage.IMG_BORDER_BLACK;
        return getBorder(textureString);
    }

    public FImage getBorderImage(String textureString) {
        return getBorder(textureString);
    }

    public Color getTint(CardView c, Texture t) {
        if (c == null)
            return borderColor(t);
        if (c.isFaceDown())
            return Color.valueOf("#171717");

        CardView.CardStateView state = c.getCurrentState();
        if (state.getColors().isColorless()) { //Moonlace -> target spell or permanent becomes colorless.
            if (state.hasDevoid()) //devoid is colorless at all zones so return its corresponding border color...
                return borderColor(t);
            return Color.valueOf("#A0A6A4");
        } else if (state.getColors().isMonoColor()) {
            if (state.getColors().hasBlack())
                return Color.valueOf("#263238");
            else if (state.getColors().hasBlue())
                return Color.valueOf("#03a9f4");
            else if (state.getColors().hasRed())
                return Color.valueOf("#f44336");
            else if (state.getColors().hasGreen())
                return Color.valueOf("#4caf50 ");
            else if (state.getColors().hasWhite())
                return Color.valueOf("#f4f3e9");
        } else if (state.getColors().isMulticolor())
            return Color.valueOf("#F8DB55");

        return borderColor(t);
    }

    public String getpixelColor(Texture i) {
        if (!i.getTextureData().isPrepared()) {
            i.getTextureData().prepare(); //prepare texture
        }
        //get pixmap from texture data
        Pixmap pixmap = i.getTextureData().consumePixmap();
        //get pixel color from x,y texture coordinate based on the image fullborder or not
        Color color = new Color(pixmap.getPixel(croppedBorderImage(i).getRegionX() + 1, croppedBorderImage(i).getRegionY() + 1));
        pixmap.dispose();
        return color.toString();
    }

    public Pair<String, Boolean> isCloserToWhite(String c) {
        if (c == null || "".equals(c))
            return Pair.of(Color.valueOf("#171717").toString(), false);
        int c_r = Integer.parseInt(c.substring(0, 2), 16);
        int c_g = Integer.parseInt(c.substring(2, 4), 16);
        int c_b = Integer.parseInt(c.substring(4, 6), 16);
        int brightness = ((c_r * 299) + (c_g * 587) + (c_b * 114)) / 1000;
        return Pair.of(c, brightness > 155);
    }

    private static class ImageRecord {
        private final String colorValue;
        private final Boolean isCloserToWhite;
        private final int cardRadius;
        private final boolean isFullBorder;

        public ImageRecord(String colorValue, Boolean isCloserToWhite, int cardRadius, boolean isFullBorder) {
            this.colorValue = colorValue;
            this.isCloserToWhite = isCloserToWhite;
            this.cardRadius = cardRadius;
            this.isFullBorder = isFullBorder;
        }

        public String colorValue() {
            return colorValue;
        }

        public Boolean isCloserToWhite() {
            return isCloserToWhite;
        }

        public int cardRadius() {
            return cardRadius;
        }

        public boolean isFullBorder() {
            return isFullBorder;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ImageRecord other = (ImageRecord) obj;
            return cardRadius == other.cardRadius &&
                   isFullBorder == other.isFullBorder &&
                   java.util.Objects.equals(colorValue, other.colorValue) &&
                   java.util.Objects.equals(isCloserToWhite, other.isCloserToWhite);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(colorValue, isCloserToWhite, cardRadius, isFullBorder);
        }

        @Override
        public String toString() {
            return "ImageRecord[colorValue=" + colorValue + ", isCloserToWhite=" + isCloserToWhite +
                   ", cardRadius=" + cardRadius + ", isFullBorder=" + isFullBorder + "]";
        }
    }
}
