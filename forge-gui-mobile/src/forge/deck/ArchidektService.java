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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import forge.StaticData;
import forge.item.PaperCard;
import forge.util.HttpUtil;

/**
 * Service class for interacting with the Archidekt API.
 * Provides methods to search for decks and fetch deck details.
 */
public class ArchidektService {

    private static final String BASE_URL = "https://archidekt.com/api";
    private static final String SEARCH_URL = BASE_URL + "/decks/v3/";
    private static final String DECK_URL = BASE_URL + "/decks/";

    /**
     * Represents a deck search result from Archidekt.
     */
    public static class DeckSearchResult {
        public final int id;
        public final String name;
        public final String owner;
        public final String format;
        public final int viewCount;

        public DeckSearchResult(int id, String name, String owner, String format, int viewCount) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.format = format;
            this.viewCount = viewCount;
        }

        @Override
        public String toString() {
            return name + " by " + owner;
        }
    }

    /**
     * Represents a card entry from an Archidekt deck.
     */
    public static class DeckCard {
        public final String name;
        public final int quantity;
        public final String category;

        public DeckCard(String name, int quantity, String category) {
            this.name = name;
            this.quantity = quantity;
            this.category = category;
        }
    }

    /**
     * Represents a full deck fetched from Archidekt.
     */
    public static class ArchidektDeck {
        public final int id;
        public final String name;
        public final String owner;
        public final String format;
        public final List<DeckCard> cards;

        public ArchidektDeck(int id, String name, String owner, String format, List<DeckCard> cards) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.format = format;
            this.cards = cards;
        }
    }

    /**
     * Search for decks on Archidekt by name.
     *
     * @param query The search query
     * @param pageSize Number of results to return (max 50)
     * @return List of deck search results, or empty list on error
     */
    public List<DeckSearchResult> searchDecks(String query, int pageSize) {
        List<DeckSearchResult> results = new ArrayList<DeckSearchResult>();

        if (query == null || query.trim().length() == 0) {
            return results;
        }

        try {
            String encodedQuery = URLEncoder.encode(query.trim(), "UTF-8");
            String url = SEARCH_URL + "?name=" + encodedQuery + "&pageSize=" + pageSize;
            String response = HttpUtil.getURL(url);

            if (response == null || response.length() == 0) {
                return results;
            }

            // Parse the JSON response manually (Java 7 compatible)
            results = parseSearchResults(response);

        } catch (UnsupportedEncodingException e) {
            // UTF-8 should always be available
            e.printStackTrace();
        }

        return results;
    }

    /**
     * Fetch a complete deck from Archidekt by ID.
     *
     * @param deckId The Archidekt deck ID
     * @return The deck details, or null on error
     */
    public ArchidektDeck fetchDeck(int deckId) {
        String url = DECK_URL + deckId + "/";
        String response = HttpUtil.getURL(url);

        if (response == null || response.length() == 0) {
            return null;
        }

        return parseDeckResponse(response);
    }

    /**
     * Convert an Archidekt deck to a Forge Deck object.
     *
     * @param archidektDeck The Archidekt deck to convert
     * @return A Forge Deck object
     */
    public Deck convertToForgeDeck(ArchidektDeck archidektDeck) {
        Deck forgeDeck = new Deck(archidektDeck.name);

        for (DeckCard card : archidektDeck.cards) {
            // Skip categories that aren't main deck or sideboard
            boolean isSideboard = "Sideboard".equalsIgnoreCase(card.category);
            boolean isMaindeck = !isSideboard && !"Maybeboard".equalsIgnoreCase(card.category);

            if (!isMaindeck && !isSideboard) {
                continue;
            }

            // Try to find the card in Forge's database
            PaperCard paperCard = findCard(card.name);
            if (paperCard != null) {
                DeckSection section = isSideboard ? DeckSection.Sideboard : DeckSection.Main;
                forgeDeck.getOrCreate(section).add(paperCard, card.quantity);
            }
        }

        return forgeDeck;
    }

    /**
     * Find a card in Forge's database by name.
     */
    private PaperCard findCard(String cardName) {
        if (cardName == null || cardName.length() == 0) {
            return null;
        }

        // Try exact match first
        PaperCard card = StaticData.instance().getCommonCards().getCard(cardName);
        if (card != null) {
            return card;
        }

        // Try without set specifier (some names include set info)
        int pipeIndex = cardName.indexOf('|');
        if (pipeIndex > 0) {
            String nameOnly = cardName.substring(0, pipeIndex).trim();
            card = StaticData.instance().getCommonCards().getCard(nameOnly);
            if (card != null) {
                return card;
            }
        }

        // Handle double-faced cards - Archidekt uses " // " separator
        int dfcIndex = cardName.indexOf(" // ");
        if (dfcIndex > 0) {
            String frontFace = cardName.substring(0, dfcIndex).trim();
            card = StaticData.instance().getCommonCards().getCard(frontFace);
            if (card != null) {
                return card;
            }
        }

        return null;
    }

    /**
     * Parse the search results JSON manually (Java 7 compatible).
     */
    private List<DeckSearchResult> parseSearchResults(String json) {
        List<DeckSearchResult> results = new ArrayList<DeckSearchResult>();

        // Find the "results" array in the JSON
        int resultsStart = json.indexOf("\"results\"");
        if (resultsStart < 0) {
            return results;
        }

        int arrayStart = json.indexOf("[", resultsStart);
        if (arrayStart < 0) {
            return results;
        }

        // Find matching bracket
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd < 0) {
            return results;
        }

        String resultsArray = json.substring(arrayStart + 1, arrayEnd);

        // Split by deck objects - find each {...} block
        int pos = 0;
        while (pos < resultsArray.length()) {
            int objStart = resultsArray.indexOf("{", pos);
            if (objStart < 0) {
                break;
            }

            int objEnd = findMatchingBrace(resultsArray, objStart);
            if (objEnd < 0) {
                break;
            }

            String deckObj = resultsArray.substring(objStart, objEnd + 1);
            DeckSearchResult result = parseDeckSearchResult(deckObj);
            if (result != null) {
                results.add(result);
            }

            pos = objEnd + 1;
        }

        return results;
    }

    /**
     * Parse a single deck search result from JSON.
     */
    private DeckSearchResult parseDeckSearchResult(String json) {
        int id = extractIntField(json, "id");
        String name = extractStringField(json, "name");
        String format = extractStringField(json, "format");
        int viewCount = extractIntField(json, "viewCount");

        // Owner is nested in an "owner" object
        String owner = "";
        int ownerStart = json.indexOf("\"owner\"");
        if (ownerStart >= 0) {
            int ownerObjStart = json.indexOf("{", ownerStart);
            if (ownerObjStart >= 0) {
                int ownerObjEnd = findMatchingBrace(json, ownerObjStart);
                if (ownerObjEnd >= 0) {
                    String ownerObj = json.substring(ownerObjStart, ownerObjEnd + 1);
                    owner = extractStringField(ownerObj, "username");
                }
            }
        }

        if (name != null && name.length() > 0) {
            return new DeckSearchResult(id, name, owner, format, viewCount);
        }
        return null;
    }

    /**
     * Parse the full deck response JSON.
     */
    private ArchidektDeck parseDeckResponse(String json) {
        int id = extractIntField(json, "id");
        String name = extractStringField(json, "name");
        String format = extractStringField(json, "format");

        // Owner is nested
        String owner = "";
        int ownerStart = json.indexOf("\"owner\"");
        if (ownerStart >= 0) {
            int ownerObjStart = json.indexOf("{", ownerStart);
            if (ownerObjStart >= 0) {
                int ownerObjEnd = findMatchingBrace(json, ownerObjStart);
                if (ownerObjEnd >= 0) {
                    String ownerObj = json.substring(ownerObjStart, ownerObjEnd + 1);
                    owner = extractStringField(ownerObj, "username");
                }
            }
        }

        // Parse cards array
        List<DeckCard> cards = new ArrayList<DeckCard>();
        int cardsStart = json.indexOf("\"cards\"");
        if (cardsStart >= 0) {
            int arrayStart = json.indexOf("[", cardsStart);
            if (arrayStart >= 0) {
                int arrayEnd = findMatchingBracket(json, arrayStart);
                if (arrayEnd >= 0) {
                    String cardsArray = json.substring(arrayStart + 1, arrayEnd);
                    cards = parseCardsArray(cardsArray);
                }
            }
        }

        if (name != null && name.length() > 0) {
            return new ArchidektDeck(id, name, owner, format, cards);
        }
        return null;
    }

    /**
     * Parse the cards array from the deck JSON.
     */
    private List<DeckCard> parseCardsArray(String cardsArray) {
        List<DeckCard> cards = new ArrayList<DeckCard>();

        int pos = 0;
        while (pos < cardsArray.length()) {
            int objStart = cardsArray.indexOf("{", pos);
            if (objStart < 0) {
                break;
            }

            int objEnd = findMatchingBrace(cardsArray, objStart);
            if (objEnd < 0) {
                break;
            }

            String cardObj = cardsArray.substring(objStart, objEnd + 1);
            DeckCard card = parseCardObject(cardObj);
            if (card != null) {
                cards.add(card);
            }

            pos = objEnd + 1;
        }

        return cards;
    }

    /**
     * Parse a single card object from the deck JSON.
     */
    private DeckCard parseCardObject(String json) {
        int quantity = extractIntField(json, "quantity");
        if (quantity <= 0) {
            quantity = 1;
        }

        String category = extractStringField(json, "category");

        // Card name is nested in a "card" object
        String cardName = "";
        int cardStart = json.indexOf("\"card\"");
        if (cardStart >= 0) {
            int cardObjStart = json.indexOf("{", cardStart);
            if (cardObjStart >= 0) {
                int cardObjEnd = findMatchingBrace(json, cardObjStart);
                if (cardObjEnd >= 0) {
                    String cardObj = json.substring(cardObjStart, cardObjEnd + 1);
                    // Try oracleCard.name first (nested), then fall back to name
                    int oracleStart = cardObj.indexOf("\"oracleCard\"");
                    if (oracleStart >= 0) {
                        int oracleObjStart = cardObj.indexOf("{", oracleStart);
                        if (oracleObjStart >= 0) {
                            int oracleObjEnd = findMatchingBrace(cardObj, oracleObjStart);
                            if (oracleObjEnd >= 0) {
                                String oracleObj = cardObj.substring(oracleObjStart, oracleObjEnd + 1);
                                cardName = extractStringField(oracleObj, "name");
                            }
                        }
                    }
                    if (cardName == null || cardName.length() == 0) {
                        cardName = extractStringField(cardObj, "name");
                    }
                }
            }
        }

        if (cardName != null && cardName.length() > 0) {
            return new DeckCard(cardName, quantity, category);
        }
        return null;
    }

    /**
     * Extract a string field from JSON.
     */
    private String extractStringField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            String value = matcher.group(1);
            // Unescape common JSON escapes
            value = value.replace("\\\"", "\"");
            value = value.replace("\\\\", "\\");
            value = value.replace("\\n", "\n");
            value = value.replace("\\t", "\t");
            return value;
        }
        return null;
    }

    /**
     * Extract an integer field from JSON.
     */
    private int extractIntField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Find the matching closing bracket for an opening bracket.
     */
    private int findMatchingBracket(String json, int openPos) {
        if (openPos < 0 || openPos >= json.length() || json.charAt(openPos) != '[') {
            return -1;
        }

        int depth = 1;
        boolean inString = false;

        for (int i = openPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '\\' && inString) {
                i++; // Skip escaped character
                continue;
            }

            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Find the matching closing brace for an opening brace.
     */
    private int findMatchingBrace(String json, int openPos) {
        if (openPos < 0 || openPos >= json.length() || json.charAt(openPos) != '{') {
            return -1;
        }

        int depth = 1;
        boolean inString = false;

        for (int i = openPos + 1; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '\\' && inString) {
                i++; // Skip escaped character
                continue;
            }

            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }
}
