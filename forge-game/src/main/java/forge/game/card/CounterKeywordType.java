package forge.game.card;

import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

public class CounterKeywordType implements CounterType {
    private final String keyword;

    // Rule 122.1b
    static ImmutableList<String> keywordCounter = ImmutableList.of(
            "Flying", "First Strike", "Double Strike", "Deathtouch", "Decayed", "Exalted", "Haste", "Hexproof",
            "Indestructible", "Lifelink", "Menace", "Reach", "Shadow", "Trample", "Vigilance");
    private static Map<String, CounterKeywordType> sMap = Maps.newHashMap();

    public CounterKeywordType(String keyword) {
        this.keyword = keyword;
    }

    public String keyword() {
        return keyword;
    }

    public static CounterKeywordType get(String s) {
        if (!sMap.containsKey(s)) {
            sMap.put(s, new CounterKeywordType(s));
        }
        return sMap.get(s);
    }

    @Override
    public String toString() {
        return keyword;
    }

    @Override
    public int hashCode() {
        return keyword != null ? keyword.hashCode() : 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        CounterKeywordType that = (CounterKeywordType) obj;
        return java.util.Objects.equals(keyword, that.keyword);
    }

    public String getName() {
        return getKeywordDescription();
    }

    public String getCounterOnCardDisplayName() {
        return getKeywordDescription();
    }

    private String getKeywordDescription() {
        if (keyword.startsWith("Hexproof:")) {
            final String[] k = keyword.split(":");
            return "Hexproof from " + k[2];
        }
        if (keyword.startsWith("Trample:")) {
            return "Trample over Planeswalkers";
        }
        return keyword;
    }

    public boolean is(CounterEnumType eType) {
        return false;
    }

    public boolean isKeywordCounter() {
        if (keyword.startsWith("Hexproof:")) {
            return true;
        }
        if (keyword.startsWith("Trample:")) {
            return true;
        }
        return keywordCounter.contains(keyword);
    }


    public int getRed() {
        return 255;
    }

    public int getGreen() {
        return 255;
    }

    public int getBlue() {
        return 255;
    }
}
