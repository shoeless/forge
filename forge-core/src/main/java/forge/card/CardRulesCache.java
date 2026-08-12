package forge.card;

import forge.util.CaseInsensitiveOrder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import forge.CardStorageReader;
import forge.card.CardType.CoreType;
import forge.card.CardType.Supertype;
import forge.card.mana.ManaCost;
import forge.util.Localizer;

/**
 * Tier-1 binary cache for parsed {@link CardRules}.
 *
 * <p>Parsing the ~32k card scripts into CardRules is the single dominant startup cost on iOS
 * (~70% of splash-to-home; ~15s on an M3 simulator, more on device). This cache serializes the
 * parsed CardRules into a compact binary file ({@code cardcache.bin}); on the next launch the
 * rules are read back directly instead of re-parsing the scripts.
 *
 * <p>The format is a hand-written {@link DataInputStream}/{@link DataOutputStream} layout — NOT
 * Java {@code Serializable}. That is deliberate: it makes the cache independent of class layout
 * and {@code serialVersionUID}, so the RoboVM/jvmdg bytecode transform used for the iOS build
 * cannot invalidate it. A {@link #FORMAT_VERSION} magic plus a caller-supplied version string
 * ({@link #computeCacheVersion}) guard against stale caches; any read error falls back to a full
 * parse and rewrites the cache.
 *
 * <p>Only Tier-1 (CardRules) is cached here. The PaperCard/CardDb "Tier-2" of the original iOS
 * work is intentionally omitted: it required swapping the card-DB {@code TreeMap} for a hash map,
 * which changes deck-generation iteration order. Keeping the TreeMap sidesteps that determinism
 * risk while still capturing the bulk of the parse cost.
 */
public class CardRulesCache {
    private static final int RULES_MAGIC = 0x46524743; // "FRGC"
    private static final int FORMAT_VERSION = 7;

    private static String cacheDir;
    private static String appVersion = "";

    public static void setCacheDir(String dir, String appVersion0) {
        cacheDir = dir;
        appVersion = appVersion0 != null ? appVersion0 : "";
    }

    // ========== Tier 1: CardRules Cache ==========

    /**
     * Load CardRules from the binary cache.
     * @param expectedVersion version string for cache invalidation
     * @return map of name -> CardRules (case-insensitive TreeMap), or null if cache is invalid/missing
     */
    public static Map<String, CardRules> loadRules(String expectedVersion, CardStorageReader.ProgressObserver observer) {
        if (cacheDir == null) return null;
        File cacheFile = new File(cacheDir, "cardcache.bin");
        if (!cacheFile.exists()) return null;
        if (observer == null) observer = CardStorageReader.ProgressObserver.emptyObserver;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(cacheFile), 1024 * 1024));
            int magic = in.readInt();
            if (magic != RULES_MAGIC) return null;
            int version = in.readInt();
            if (version != FORMAT_VERSION) return null;
            String cachedVersion = in.readUTF();
            if (!expectedVersion.equals(cachedVersion)) return null;

            int count = in.readInt();
            observer.setOperationName(Localizer.getInstance().getMessageorUseDefault(
                    "splash.loading.cards-cache", "Loading card database"), true);
            observer.report(0, count);
            // Keep TreeMap (case-insensitive) to preserve card-DB iteration order — see class doc.
            Map<String, CardRules> result = new TreeMap<>(CaseInsensitiveOrder.INSTANCE);
            for (int i = 0; i < count; i++) {
                CardRules rules = readCardRules(in);
                if (rules != null) {
                    result.put(rules.getName(), rules);
                }
                if ((i & 511) == 511) {
                    observer.report(i + 1, count);
                }
            }
            observer.report(count, count);
            return result;
        } catch (Exception e) {
            System.err.println("FORGE: Failed to read card rules cache: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Save CardRules to the binary cache.
     * @param allRules map of all CardRules to save (both regular and variant)
     * @param version version string for cache invalidation
     */
    public static void saveRules(Map<String, CardRules> allRules, String version) {
        if (cacheDir == null) return;
        File dir = new File(cacheDir);
        if (!dir.exists()) dir.mkdirs();
        File cacheFile = new File(cacheDir, "cardcache.bin");

        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(cacheFile), 1024 * 1024));
            out.writeInt(RULES_MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(version);

            // Deduplicate rules (flavor-name entries map to the same CardRules object).
            Set<String> written = new HashSet<>();
            List<CardRules> uniqueRules = new ArrayList<>();
            for (CardRules rules : allRules.values()) {
                String key = rules.getNormalizedName();
                if (key == null) key = rules.getName();
                if (written.add(key)) {
                    uniqueRules.add(rules);
                }
            }

            out.writeInt(uniqueRules.size());
            for (CardRules rules : uniqueRules) {
                writeCardRules(out, rules);
            }
        } catch (Exception e) {
            System.err.println("FORGE: Failed to write card rules cache: " + e.getMessage());
            e.printStackTrace();
            cacheFile.delete();
        } finally {
            closeQuietly(out);
        }
    }

    // ========== CardRules Serialization ==========

    private static void writeCardRules(DataOutputStream out, CardRules rules) throws IOException {
        writeNullableUTF(out, rules.getNormalizedName());
        out.writeUTF(rules.getSplitType().name());
        out.writeUTF(rules.getMeldWith());
        out.writeUTF(rules.getPartnerWith());
        out.writeUTF(rules.getPartnerType());
        out.writeBoolean(rules.getAddsWildCardColor());
        out.writeInt(rules.getSetColorID());
        out.writeInt(rules.getHand());
        out.writeInt(rules.getLife());
        ColorSet ci = rules.getColorIdentity();
        out.writeByte(ci != null ? ci.ordinal() : 0);

        // Tokens
        List<String> tokens = rules.getTokens();
        out.writeInt(tokens != null ? tokens.size() : 0);
        if (tokens != null) {
            for (String token : tokens) {
                out.writeUTF(token);
            }
        }

        // Supported functional variants
        Set<String> variants = rules.getSupportedFunctionalVariants();
        if (variants == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(variants.size());
            for (String v : variants) {
                out.writeUTF(v);
            }
        }

        // AI Hints
        CardAiHints aiHints = rules.getAiHints();
        if (aiHints != null) {
            out.writeBoolean(true);
            out.writeBoolean(aiHints.getRemAIDecks());
            out.writeBoolean(aiHints.getRemRandomDecks());
            out.writeBoolean(aiHints.getRemNonCommanderDecks());
            writeDeckHints(out, aiHints.getDeckHints());
            writeDeckHints(out, aiHints.getDeckNeeds());
            writeDeckHints(out, aiHints.getDeckHas());
        } else {
            out.writeBoolean(false);
        }

        // Faces: main + other + 5 specialize
        writeCardFace(out, rules.getMainPart());
        writeNullableCardFace(out, rules.getOtherPart());

        if (rules.getSplitType() == CardSplitType.Specialize) {
            Map<CardStateName, ICardFace> specParts = rules.getSpecializeParts();
            if (specParts != null) {
                writeNullableCardFace(out, specParts.get(CardStateName.SpecializeW));
                writeNullableCardFace(out, specParts.get(CardStateName.SpecializeU));
                writeNullableCardFace(out, specParts.get(CardStateName.SpecializeB));
                writeNullableCardFace(out, specParts.get(CardStateName.SpecializeR));
                writeNullableCardFace(out, specParts.get(CardStateName.SpecializeG));
            } else {
                for (int i = 0; i < 5; i++) {
                    writeNullableCardFace(out, null);
                }
            }
        }
    }

    private static CardRules readCardRules(DataInputStream in) throws IOException {
        String normalizedName = readNullableUTF(in);
        CardSplitType splitType = CardSplitType.smartValueOf(in.readUTF());
        String meldWith = in.readUTF();
        String partnerWith = in.readUTF();
        String partnerType = in.readUTF();
        // getAddsWildCardColor() is computed from oracle text on this codebase, so there is no
        // field to restore — read the boolean to keep the stream position and discard it.
        in.readBoolean();
        int setColorID = in.readInt();
        int deltaHand = in.readInt();
        int deltaLife = in.readInt();
        // colorIdentity ordinal is written for forward-compat but recomputed by CardRules; discard.
        in.readByte();

        // Tokens
        int tokenCount = in.readInt();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < tokenCount; i++) {
            tokens.add(in.readUTF());
        }

        // Supported functional variants
        int variantCount = in.readInt();
        Set<String> supportedVariants = null;
        if (variantCount >= 0) {
            supportedVariants = new HashSet<>();
            for (int i = 0; i < variantCount; i++) {
                supportedVariants.add(in.readUTF());
            }
        }

        // AI Hints
        CardAiHints aiHints;
        if (in.readBoolean()) {
            boolean remAI = in.readBoolean();
            boolean remRandom = in.readBoolean();
            boolean remNonCommander = in.readBoolean();
            DeckHints hints = readDeckHints(in);
            DeckHints needs = readDeckHints(in);
            DeckHints has = readDeckHints(in);
            aiHints = new CardAiHints(remAI, remRandom, remNonCommander, hints, needs, has);
        } else {
            aiHints = new CardAiHints(false, false, false, null, null, null);
        }

        // Faces
        CardFace mainFace = readCardFace(in);
        CardFace otherFace = readNullableCardFace(in);

        CardFace[] faces = new CardFace[7];
        faces[0] = mainFace;
        faces[1] = otherFace;

        if (splitType == CardSplitType.Specialize) {
            faces[2] = readNullableCardFace(in);
            faces[3] = readNullableCardFace(in);
            faces[4] = readNullableCardFace(in);
            faces[5] = readNullableCardFace(in);
            faces[6] = readNullableCardFace(in);
        }

        // Normalize null fields only. assignMissingFields would also re-merge the base lists
        // into the functional variants, which were serialized already merged — doubling them.
        for (CardFace f : faces) {
            if (f == null) continue;
            f.normalizeMissingFields();
            if (f.hasFunctionalVariants()) {
                for (ICardFace v : f.getFunctionalVariants().values()) {
                    ((CardFace) v).normalizeMissingFields();
                }
            }
        }

        // Construct CardRules
        CardRules result = new CardRules(faces, splitType, aiHints);
        result.normalizedName = normalizedName;
        result.meldWith = meldWith;
        result.partnerWith = partnerWith;
        result.partnerType = partnerType;
        result.setColorID = setColorID;
        if (!tokens.isEmpty()) {
            result.tokens = tokens;
        }
        if (deltaHand != 0 || deltaLife != 0) {
            result.deltaHand = deltaHand;
            result.deltaLife = deltaLife;
        }
        result.supportedFunctionalVariants = supportedVariants;

        return result;
    }

    // ========== CardFace Serialization ==========

    private static void writeCardFace(DataOutputStream out, ICardFace face) throws IOException {
        out.writeUTF(face.getName());
        writeNullableUTF(out, face.getFlavorName());

        // ManaCost
        ManaCost mc = face.getManaCost();
        if (mc != null) {
            out.writeBoolean(true);
            out.writeUTF(ManaCost.serialize(mc));
        } else {
            out.writeBoolean(false);
        }

        // Color
        ColorSet clr = face.getColor();
        if (clr != null) {
            out.writeBoolean(true);
            out.writeByte(clr.ordinal());
        } else {
            out.writeBoolean(false);
        }

        // CardType - serialize components
        if (face.getType() != null) {
            out.writeBoolean(true);
            writeCardType(out, (CardType) face.getType());
        } else {
            out.writeBoolean(false);
        }

        // Oracle text
        writeNullableUTF(out, face.getOracleText());

        // P/T
        out.writeInt(face.getIntPower());
        out.writeInt(face.getIntToughness());
        writeNullableUTF(out, face.getPower());
        writeNullableUTF(out, face.getToughness());

        // Loyalty, Defense
        out.writeUTF(face.getInitialLoyalty());
        out.writeUTF(face.getDefense());

        // Attraction lights
        Set<Integer> lights = face.getAttractionLights();
        if (lights == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(lights.size());
            for (Integer light : lights) {
                out.writeInt(light);
            }
        }

        // Non-ability text
        writeNullableUTF(out, face.getNonAbilityText());

        // String lists: keywords, abilities, staticAbilities, triggers, draftActions, replacements
        writeStringIterable(out, face.getKeywords());
        writeStringIterable(out, face.getAbilities());
        writeStringIterable(out, face.getStaticAbilities());
        writeStringIterable(out, face.getTriggers());
        writeStringIterable(out, face.getDraftActions());
        writeStringIterable(out, face.getReplacements());

        // Variables (SVars)
        Iterable<Map.Entry<String, String>> vars = face.getVariables();
        if (vars == null) {
            out.writeInt(-1);
        } else {
            List<Map.Entry<String, String>> varList = new ArrayList<>();
            for (Map.Entry<String, String> entry : vars) {
                varList.add(entry);
            }
            out.writeInt(varList.size());
            for (Map.Entry<String, String> entry : varList) {
                writeNullableUTF(out, entry.getKey());
                writeNullableUTF(out, entry.getValue());
            }
        }

        // Functional variants
        if (face.hasFunctionalVariants()) {
            Map<String, ? extends ICardFace> funcVars = face.getFunctionalVariants();
            out.writeInt(funcVars.size());
            for (Map.Entry<String, ? extends ICardFace> entry : funcVars.entrySet()) {
                out.writeUTF(entry.getKey());
                writeCardFace(out, entry.getValue()); // recursive, but variants don't have nested variants
            }
        } else {
            out.writeInt(-1);
        }
    }

    private static CardFace readCardFace(DataInputStream in) throws IOException {
        String name = in.readUTF();
        String flavorName = readNullableUTF(in);

        CardFace face = new CardFace(name);
        if (flavorName != null) face.setFlavorName(flavorName);

        // ManaCost
        if (in.readBoolean()) {
            String manaCostStr = in.readUTF();
            face.setManaCost(ManaCost.deserialize(manaCostStr));
        }

        // Color
        if (in.readBoolean()) {
            byte colorOrdinal = in.readByte();
            face.setColor(ColorSet.values()[colorOrdinal & 0xFF]);
        }

        // CardType
        if (in.readBoolean()) {
            face.setType(readCardType(in));
        }

        // Oracle text
        String oracle = readNullableUTF(in);
        if (oracle != null) face.setOracleText(oracle);

        // P/T
        in.readInt(); // intPower (recomputed from power text)
        in.readInt(); // intToughness (recomputed from toughness text)
        String power = readNullableUTF(in);
        String toughness = readNullableUTF(in);
        if (power != null && toughness != null) {
            face.setPtText(power + "/" + toughness);
        }

        // Loyalty, Defense
        String loyalty = in.readUTF();
        String defense = in.readUTF();
        if (!loyalty.isEmpty()) face.setInitialLoyalty(loyalty);
        if (!defense.isEmpty()) face.setDefense(defense);

        // Attraction lights
        int lightCount = in.readInt();
        if (lightCount >= 0) {
            StringBuilder lightStr = new StringBuilder();
            for (int i = 0; i < lightCount; i++) {
                if (i > 0) lightStr.append(" ");
                lightStr.append(in.readInt());
            }
            if (lightStr.length() > 0) {
                face.setAttractionLights(lightStr.toString());
            }
        }

        // Non-ability text
        String nonAbilityText = readNullableUTF(in);
        if (nonAbilityText != null) face.setNonAbilityText(nonAbilityText);

        // String lists
        readStringList(in, new StringAdder() { public void add(String s) { face.addKeyword(s); } });
        readStringList(in, new StringAdder() { public void add(String s) { face.addAbility(s); } });
        readStringList(in, new StringAdder() { public void add(String s) { face.addStaticAbility(s); } });
        readStringList(in, new StringAdder() { public void add(String s) { face.addTrigger(s); } });
        readStringList(in, new StringAdder() { public void add(String s) { face.addDraftAction(s); } });
        readStringList(in, new StringAdder() { public void add(String s) { face.addReplacementEffect(s); } });

        // Variables (SVars)
        int varCount = in.readInt();
        if (varCount >= 0) {
            for (int i = 0; i < varCount; i++) {
                String key = readNullableUTF(in);
                String value = readNullableUTF(in);
                if (key != null) {
                    face.addSVar(key, value != null ? value : "");
                }
            }
        }

        // Functional variants
        int funcVarCount = in.readInt();
        if (funcVarCount >= 0) {
            for (int i = 0; i < funcVarCount; i++) {
                String variantName = in.readUTF();
                CardFace variantFace = readCardFace(in);
                CardFace existing = (CardFace) face.getOrCreateFunctionalVariant(variantName);
                // Copy all fields from the read variant into the created one.
                copyFaceFields(variantFace, existing);
            }
        }

        return face;
    }

    private static void copyFaceFields(CardFace source, CardFace target) {
        if (source.getFlavorName() != null) target.setFlavorName(source.getFlavorName());
        target.setManaCost(source.getManaCost());
        target.setColor(source.getColor());
        target.setType(source.getType());
        if (source.getOracleText() != null) target.setOracleText(source.getOracleText());
        if (source.getPower() != null && source.getToughness() != null) {
            target.setPtText(source.getPower() + "/" + source.getToughness());
        }
        if (source.getInitialLoyalty() != null && !source.getInitialLoyalty().isEmpty()) {
            target.setInitialLoyalty(source.getInitialLoyalty());
        }
        if (source.getDefense() != null && !source.getDefense().isEmpty()) {
            target.setDefense(source.getDefense());
        }
        if (source.getNonAbilityText() != null) target.setNonAbilityText(source.getNonAbilityText());
        if (source.getKeywords() != null) {
            for (String kw : source.getKeywords()) target.addKeyword(kw);
        }
        if (source.getAbilities() != null) {
            for (String ab : source.getAbilities()) target.addAbility(ab);
        }
        if (source.getStaticAbilities() != null) {
            for (String sa : source.getStaticAbilities()) target.addStaticAbility(sa);
        }
        if (source.getTriggers() != null) {
            for (String tr : source.getTriggers()) target.addTrigger(tr);
        }
        if (source.getDraftActions() != null) {
            for (String da : source.getDraftActions()) target.addDraftAction(da);
        }
        if (source.getReplacements() != null) {
            for (String re : source.getReplacements()) target.addReplacementEffect(re);
        }
        Iterable<Map.Entry<String, String>> vars = source.getVariables();
        if (vars != null) {
            for (Map.Entry<String, String> entry : vars) {
                target.addSVar(entry.getKey(), entry.getValue());
            }
        }
    }

    // ========== CardType Serialization ==========

    private static void writeCardType(DataOutputStream out, CardType type) throws IOException {
        if (type == null) {
            out.writeShort(0);
            out.writeByte(0);
            out.writeInt(0);
            out.writeBoolean(false);
            out.writeInt(0);
            return;
        }

        // Core types as bitmask (fits in short)
        short coreMask = 0;
        for (CoreType ct : type.getCoreTypes()) {
            coreMask |= (1 << ct.ordinal());
        }
        out.writeShort(coreMask);

        // Supertypes as bitmask (fits in byte)
        byte superMask = 0;
        for (Supertype st : type.getSupertypes()) {
            superMask |= (1 << st.ordinal());
        }
        out.writeByte(superMask);

        // Subtypes
        Collection<String> subtypes = type.getSubtypes();
        out.writeInt(subtypes.size());
        for (String sub : subtypes) {
            out.writeUTF(sub);
        }

        // allCreatureTypes
        out.writeBoolean(type.hasAllCreatureTypes());

        // excludedCreatureSubtypes
        Iterable<String> excluded = type.getExcludedCreatureSubTypes();
        List<String> excludedList = new ArrayList<>();
        for (String s : excluded) {
            excludedList.add(s);
        }
        out.writeInt(excludedList.size());
        for (String s : excludedList) {
            out.writeUTF(s);
        }
    }

    private static CardType readCardType(DataInputStream in) throws IOException {
        short coreMask = in.readShort();
        byte superMask = in.readByte();

        // Reconstruct the type string for CardType.parse()
        StringBuilder typeStr = new StringBuilder();

        // Supertypes first
        for (Supertype st : Supertype.values()) {
            if ((superMask & (1 << st.ordinal())) != 0) {
                if (typeStr.length() > 0) typeStr.append(" ");
                typeStr.append(st.name());
            }
        }

        // Core types
        for (CoreType ct : CoreType.values()) {
            if ((coreMask & (1 << ct.ordinal())) != 0) {
                if (typeStr.length() > 0) typeStr.append(" ");
                typeStr.append(ct.name());
            }
        }

        // Subtypes
        int subtypeCount = in.readInt();
        if (subtypeCount > 0) {
            typeStr.append(" -");
            for (int i = 0; i < subtypeCount; i++) {
                typeStr.append(" ").append(in.readUTF());
            }
        }

        boolean allCreatureTypes = in.readBoolean();

        // Excluded creature subtypes
        int excludedCount = in.readInt();
        List<String> excludedTypes = new ArrayList<>();
        for (int i = 0; i < excludedCount; i++) {
            excludedTypes.add(in.readUTF());
        }

        CardType result = CardType.parse(typeStr.toString(), false);
        if (allCreatureTypes) {
            result.allCreatureTypes = true;
        }
        for (String excluded : excludedTypes) {
            result.excludedCreatureSubtypes.add(excluded);
        }
        return result;
    }

    // ========== DeckHints Serialization ==========

    private static void writeDeckHints(DataOutputStream out, DeckHints hints) throws IOException {
        if (hints == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(hints.toRawString());
        }
    }

    private static DeckHints readDeckHints(DataInputStream in) throws IOException {
        boolean present = in.readBoolean();
        if (!present) return null;
        String raw = in.readUTF();
        if (raw.isEmpty()) return null;
        return new DeckHints(raw);
    }

    // ========== Utility Methods ==========

    private static void writeNullableUTF(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(s);
        }
    }

    private static String readNullableUTF(DataInputStream in) throws IOException {
        boolean present = in.readBoolean();
        if (!present) return null;
        return in.readUTF();
    }

    private static void writeNullableCardFace(DataOutputStream out, ICardFace face) throws IOException {
        if (face == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            writeCardFace(out, face);
        }
    }

    private static CardFace readNullableCardFace(DataInputStream in) throws IOException {
        boolean present = in.readBoolean();
        if (!present) return null;
        return readCardFace(in);
    }

    private static void writeStringIterable(DataOutputStream out, Iterable<String> items) throws IOException {
        if (items == null) {
            out.writeInt(-1);
            return;
        }
        List<String> list = new ArrayList<>();
        for (String s : items) {
            list.add(s);
        }
        out.writeInt(list.size());
        for (String s : list) {
            out.writeUTF(s);
        }
    }

    private interface StringAdder {
        void add(String s);
    }

    private static void readStringList(DataInputStream in, StringAdder adder) throws IOException {
        int count = in.readInt();
        if (count < 0) return;
        for (int i = 0; i < count; i++) {
            adder.add(in.readUTF());
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    /**
     * Compute a cache version string from edition data. Changes when editions are added/removed
     * or when the card count within editions changes, invalidating a stale cache.
     */
    public static String computeCacheVersion(CardEdition.Collection editions, long cardSourceTimestamp) {
        int editionCount = 0;
        int totalCards = 0;
        for (CardEdition e : editions) {
            editionCount++;
            totalCards += e.getAllCardsInSet().size();
        }
        // cardSourceTimestamp (cardsfolder.zip mtime) invalidates the cache when a card body is
        // edited without changing the edition/card counts — otherwise the stale parse is served.
        // appVersion: a new app build invalidates the cache even when the card data is unchanged
        return FORMAT_VERSION + ":" + appVersion + ":" + editionCount + ":" + totalCards + ":" + cardSourceTimestamp;
    }
}
