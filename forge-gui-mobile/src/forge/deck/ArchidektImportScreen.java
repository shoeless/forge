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
package forge.deck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.deck.ArchidektService.ArchidektDeck;
import forge.deck.ArchidektService.DeckCard;
import forge.deck.ArchidektService.DeckSearchResult;
import forge.gui.FThreads;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FEvent;
import forge.toolbox.FEvent.FEventHandler;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.toolbox.FTextField;
import forge.util.Utils;
import java.util.function.Consumer;

/**
 * Screen for searching and importing decks from Archidekt.
 */
public class ArchidektImportScreen extends FScreen {
    private static final float PADDING = Utils.scale(10);
    private static final float BUTTON_HEIGHT = Utils.scale(40);

    private final FTextField txtSearch;
    private final FButton btnSearch;
    private final FList<DeckSearchResult> lstResults;
    private final FLabel lblStatus;
    private final FButton btnImport;
    private final FLabel lblPreview;
    private final FList<String> lstPreview;

    private final ArchidektService service;
    private Consumer<Deck> importCallback;
    private ArchidektDeck selectedDeck;

    public ArchidektImportScreen() {
        super("Import from Archidekt");

        service = new ArchidektService();

        // Search field
        txtSearch = add(new FTextField());
        txtSearch.setGhostText("Enter deck name to search...");

        // Search button
        btnSearch = add(new FButton("Search", new FEventHandler() {
            @Override
            public void handleEvent(FEvent e) {
                performSearch();
            }
        }));

        // Status label
        lblStatus = add(new FLabel.Builder().text("").font(FSkinFont.get(12)).align(Align.center).build());

        // Results list
        lstResults = add(new FList<DeckSearchResult>());
        lstResults.setListItemRenderer(new SearchResultRenderer());

        // Preview label
        lblPreview = add(new FLabel.Builder().text("Deck Preview").font(FSkinFont.get(14)).align(Align.left).build());
        lblPreview.setVisible(false);

        // Preview list
        lstPreview = add(new FList<String>());
        lstPreview.setVisible(false);

        // Import button
        btnImport = add(new FButton("Import Deck", new FEventHandler() {
            @Override
            public void handleEvent(FEvent e) {
                importSelectedDeck();
            }
        }));
        btnImport.setVisible(false);
    }

    /**
     * Set the callback to be called when a deck is imported.
     */
    public void setImportCallback(Consumer<Deck> callback) {
        this.importCallback = callback;
    }

    /**
     * Perform the deck search.
     */
    private void performSearch() {
        final String query = txtSearch.getText();
        if (query == null || query.trim().length() == 0) {
            lblStatus.setText("Please enter a search term");
            return;
        }

        lblStatus.setText("Searching...");
        lstResults.clear();
        hidePreview();

        FThreads.invokeInBackgroundThread(new Runnable() {
            @Override
            public void run() {
                final List<DeckSearchResult> results = service.searchDecks(query, 20);

                FThreads.invokeInEdtLater(new Runnable() {
                    @Override
                    public void run() {
                        if (results.isEmpty()) {
                            lblStatus.setText("No decks found");
                        } else {
                            lblStatus.setText("Found " + results.size() + " deck(s) - tap to preview");
                            lstResults.setListData(results);
                        }
                    }
                });
            }
        });
    }

    /**
     * Load and preview a selected deck.
     */
    private void loadDeckPreview(final DeckSearchResult searchResult) {
        lblStatus.setText("Loading deck...");

        FThreads.invokeInBackgroundThread(new Runnable() {
            @Override
            public void run() {
                final ArchidektDeck deck = service.fetchDeck(searchResult.id);

                FThreads.invokeInEdtLater(new Runnable() {
                    @Override
                    public void run() {
                        if (deck == null) {
                            lblStatus.setText("Failed to load deck");
                            hidePreview();
                        } else {
                            selectedDeck = deck;
                            showDeckPreview(deck);
                            lblStatus.setText("Loaded: " + deck.name + " by " + deck.owner);
                        }
                    }
                });
            }
        });
    }

    /**
     * Show the deck preview.
     */
    private void showDeckPreview(ArchidektDeck deck) {
        // Count total cards including quantities
        int totalCards = 0;
        for (DeckCard card : deck.cards) {
            totalCards += card.quantity;
        }
        lblPreview.setText("Deck Preview (" + totalCards + " cards)");
        lblPreview.setVisible(true);

        // Build preview list
        ArrayList<String> previewLines = new ArrayList<String>();
        previewLines.add("Format: " + (deck.format != null ? deck.format : "Unknown"));
        previewLines.add("---");

        // Group by category
        Map<String, List<DeckCard>> byCategory = new LinkedHashMap<String, List<DeckCard>>();
        for (DeckCard card : deck.cards) {
            String category = card.category != null ? card.category : "Main";
            List<DeckCard> categoryCards = byCategory.get(category);
            if (categoryCards == null) {
                categoryCards = new ArrayList<DeckCard>();
                byCategory.put(category, categoryCards);
            }
            categoryCards.add(card);
        }

        // Add cards by category
        for (Map.Entry<String, List<DeckCard>> entry : byCategory.entrySet()) {
            previewLines.add("[" + entry.getKey() + "]");
            for (DeckCard card : entry.getValue()) {
                previewLines.add("  " + card.quantity + "x " + card.name);
            }
        }

        lstPreview.setListData(previewLines);
        lstPreview.setVisible(true);
        btnImport.setVisible(true);

        revalidate();
    }

    /**
     * Hide the preview section.
     */
    private void hidePreview() {
        selectedDeck = null;
        lblPreview.setVisible(false);
        lstPreview.setVisible(false);
        btnImport.setVisible(false);
        revalidate();
    }

    /**
     * Import the selected deck.
     */
    private void importSelectedDeck() {
        if (selectedDeck == null) {
            return;
        }

        btnImport.setEnabled(false);
        lblStatus.setText("Importing...");

        FThreads.invokeInBackgroundThread(new Runnable() {
            @Override
            public void run() {
                final Deck forgeDeck = service.convertToForgeDeck(selectedDeck);

                FThreads.invokeInEdtLater(new Runnable() {
                    @Override
                    public void run() {
                        btnImport.setEnabled(true);

                        int mainCount = forgeDeck.getMain() != null ? forgeDeck.getMain().countAll() : 0;
                        int sideCount = forgeDeck.has(DeckSection.Sideboard) ? forgeDeck.get(DeckSection.Sideboard).countAll() : 0;

                        if (mainCount == 0 && sideCount == 0) {
                            FOptionPane.showErrorDialog("No cards could be imported. The cards may not exist in Forge's database.");
                            return;
                        }

                        lblStatus.setText("Imported " + mainCount + " main / " + sideCount + " sideboard cards");

                        if (importCallback != null) {
                            importCallback.accept(forgeDeck);
                            Forge.back();
                        } else {
                            FOptionPane.showMessageDialog(
                                    "Successfully imported deck: " + forgeDeck.getName() + "\n" +
                                            "Main deck: " + mainCount + " cards\n" +
                                            "Sideboard: " + sideCount + " cards",
                                    "Import Complete");
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float x = PADDING;
        float y = startY + PADDING;
        float w = width - 2 * PADDING;

        // Search row
        float searchBtnWidth = Utils.scale(80);
        txtSearch.setBounds(x, y, w - searchBtnWidth - PADDING, BUTTON_HEIGHT);
        btnSearch.setBounds(x + w - searchBtnWidth, y, searchBtnWidth, BUTTON_HEIGHT);
        y += BUTTON_HEIGHT + PADDING;

        // Status label
        lblStatus.setBounds(x, y, w, Utils.scale(20));
        y += Utils.scale(20) + PADDING;

        // Calculate space for lists
        float remainingHeight = height - y - PADDING;

        if (lblPreview.isVisible()) {
            // Split space between results and preview
            float listHeight = (remainingHeight - BUTTON_HEIGHT - PADDING * 2 - Utils.scale(20)) / 2;

            lstResults.setBounds(x, y, w, listHeight);
            y += listHeight + PADDING;

            lblPreview.setBounds(x, y, w, Utils.scale(20));
            y += Utils.scale(20);

            lstPreview.setBounds(x, y, w, listHeight);
            y += listHeight + PADDING;

            btnImport.setBounds(x, y, w, BUTTON_HEIGHT);
        } else {
            // Full space for results
            lstResults.setBounds(x, y, w, remainingHeight);
        }
    }

    /**
     * Custom renderer for search results.
     */
    private class SearchResultRenderer extends FList.ListItemRenderer<DeckSearchResult> {

        @Override
        public float getItemHeight() {
            return Utils.AVG_FINGER_HEIGHT;
        }

        @Override
        public boolean tap(Integer index, DeckSearchResult value, float x, float y, int count) {
            loadDeckPreview(value);
            return true;
        }

        @Override
        public void drawValue(Graphics g, Integer index, DeckSearchResult value, FSkinFont font, FSkinColor foreColor, FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
            if (pressed) {
                g.fillRect(FList.getPressedColor(), x - FList.PADDING, y, w + 2 * FList.PADDING, h);
            }

            float textHeight = font.getLineHeight();
            float padding = FList.PADDING;

            // Draw deck name
            g.drawText(value.name, font, foreColor, x + padding, y + padding, w - 2 * padding, textHeight, false, Align.left, false);

            // Draw owner and format
            FSkinFont smallFont = FSkinFont.get(11);
            String subtitle = "by " + value.owner;
            if (value.format != null && value.format.length() > 0) {
                subtitle += " | " + value.format;
            }
            g.drawText(subtitle, smallFont, foreColor.alphaColor(0.7f), x + padding, y + textHeight + padding, w - 2 * padding, textHeight, false, Align.left, false);

            // Draw separator line
            float lineY = y + h - FList.LINE_THICKNESS / 2;
            g.drawLine(FList.LINE_THICKNESS, FList.getLineColor(), x, lineY, x + w, lineY);
        }
    }

    /**
     * Show the Archidekt import screen.
     *
     * @param callback Callback to receive the imported deck
     */
    public static void show(Consumer<Deck> callback) {
        ArchidektImportScreen screen = new ArchidektImportScreen();
        screen.setImportCallback(callback);
        Forge.openScreen(screen);
    }
}
