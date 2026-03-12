package forge.gamemodes.net;

import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;
import forge.trackable.TrackableTypes;
import forge.trackable.Tracker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts view objects to/from lightweight ID-only stubs for network transfer.
 * The client already has full game state from setGameView; most method args
 * redundantly re-serialize objects the client already has. Stubs reduce message
 * size by 99%+ (e.g. 51KB → 400 bytes).
 */
public final class NetStubs {
    private NetStubs() { }

    /**
     * Replaces full view objects in args with ID-only stubs (in-place).
     * Returns the same array for convenience.
     */
    public static Object[] stripArgs(final Object[] args) {
        for (int i = 0; i < args.length; i++) {
            args[i] = stripObject(args[i]);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Object stripObject(final Object obj) {
        if (obj == null) {
            return null;
        }

        // Never strip GameView — it IS the full state payload
        if (obj instanceof GameView) {
            return obj;
        }

        // Strip view objects to ID-only stubs
        if (obj instanceof PlayerView) {
            return new PlayerView(((PlayerView) obj).getId(), null);
        }
        if (obj instanceof CardView) {
            return new CardView(((CardView) obj).getId());
        }
        if (obj instanceof SpellAbilityView) {
            return stripSpellAbilityView((SpellAbilityView) obj);
        }

        // Strip PlayerZoneUpdate — replace inner PlayerView with stub
        if (obj instanceof PlayerZoneUpdate) {
            PlayerZoneUpdate pzu = (PlayerZoneUpdate) obj;
            PlayerView stubPlayer = new PlayerView(pzu.getPlayer().getId(), null);
            return new PlayerZoneUpdate(stubPlayer, pzu.getZones());
        }

        // Strip PlayerZoneUpdates — iterate and strip each entry
        if (obj instanceof PlayerZoneUpdates) {
            PlayerZoneUpdates stripped = new PlayerZoneUpdates();
            for (PlayerZoneUpdate pzu : (PlayerZoneUpdates) obj) {
                stripped.add((PlayerZoneUpdate) stripObject(pzu));
            }
            return stripped;
        }

        // Strip TrackableCollection — must preserve type (not convert to ArrayList)
        if (obj instanceof TrackableCollection) {
            TrackableCollection<TrackableObject> original = (TrackableCollection<TrackableObject>) obj;
            TrackableCollection<TrackableObject> stripped = new TrackableCollection<TrackableObject>();
            for (TrackableObject item : original) {
                stripped.add((TrackableObject) stripObject(item));
            }
            return stripped;
        }

        // Strip List — create new ArrayList with stripped elements
        if (obj instanceof List) {
            List<Object> original = (List<Object>) obj;
            List<Object> stripped = new ArrayList<Object>(original.size());
            for (Object item : original) {
                stripped.add(stripObject(item));
            }
            return stripped;
        }

        // Strip Map — strip both keys and values
        if (obj instanceof Map) {
            Map<Object, Object> original = (Map<Object, Object>) obj;
            Map<Object, Object> stripped = new HashMap<Object, Object>(original.size());
            for (Map.Entry<Object, Object> entry : original.entrySet()) {
                stripped.put(stripObject(entry.getKey()), stripObject(entry.getValue()));
            }
            return stripped;
        }

        // Strip other Iterables — convert to ArrayList with stripped elements
        if (obj instanceof Iterable) {
            List<Object> stripped = new ArrayList<Object>();
            for (Object item : (Iterable<Object>) obj) {
                stripped.add(stripObject(item));
            }
            return stripped;
        }

        // Primitives, Strings, enums, etc. — pass through unchanged
        return obj;
    }

    /**
     * Resolves only PlayerView stubs in args to full local objects from the tracker (in-place).
     * Does NOT resolve CardView or SpellAbilityView — the tracker may contain stale
     * shell-game objects for those. Only PlayerView is reliably kept in sync via
     * replicatePlayerView during setGameView processing.
     */
    public static void resolvePlayerViews(final Object[] args, final Tracker tracker) {
        for (int i = 0; i < args.length; i++) {
            args[i] = resolvePlayerViewOnly(args[i], tracker);
        }
    }

    /**
     * Resolves ALL view stubs (PlayerView, CardView) in args to full local
     * objects from the tracker (in-place). Only safe after setGameView has been
     * processed, so the tracker has current CardView data. Used for coalesced
     * updateCards/updateZones where stubs are ID-only refresh signals.
     */
    public static void resolveAllViews(final Object[] args, final Tracker tracker) {
        for (int i = 0; i < args.length; i++) {
            args[i] = resolveAllViewsInternal(args[i], tracker);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolvePlayerViewOnly(final Object obj, final Tracker tracker) {
        if (obj == null || tracker == null) {
            return obj;
        }

        // Resolve PlayerView stubs
        if (obj instanceof PlayerView) {
            PlayerView resolved = tracker.getObj(TrackableTypes.PlayerViewType, ((PlayerView) obj).getId());
            return resolved != null ? resolved : obj;
        }

        // Skip CardView and SpellAbilityView — do NOT resolve
        if (obj instanceof CardView || obj instanceof SpellAbilityView) {
            return obj;
        }

        // Resolve PlayerZoneUpdate — replace inner PlayerView
        if (obj instanceof PlayerZoneUpdate) {
            PlayerZoneUpdate pzu = (PlayerZoneUpdate) obj;
            PlayerView resolved = tracker.getObj(TrackableTypes.PlayerViewType, pzu.getPlayer().getId());
            if (resolved != null) {
                return new PlayerZoneUpdate(resolved, pzu.getZones());
            }
            return obj;
        }

        // Resolve PlayerZoneUpdates
        if (obj instanceof PlayerZoneUpdates) {
            PlayerZoneUpdates resolved = new PlayerZoneUpdates();
            for (PlayerZoneUpdate pzu : (PlayerZoneUpdates) obj) {
                resolved.add((PlayerZoneUpdate) resolvePlayerViewOnly(pzu, tracker));
            }
            return resolved;
        }

        // Resolve TrackableCollection — may contain PlayerViews
        if (obj instanceof TrackableCollection) {
            TrackableCollection<TrackableObject> original = (TrackableCollection<TrackableObject>) obj;
            TrackableCollection<TrackableObject> resolved = new TrackableCollection<TrackableObject>();
            for (TrackableObject item : original) {
                resolved.add((TrackableObject) resolvePlayerViewOnly(item, tracker));
            }
            return resolved;
        }

        // Resolve List
        if (obj instanceof List) {
            List<Object> original = (List<Object>) obj;
            List<Object> resolved = new ArrayList<Object>(original.size());
            for (Object item : original) {
                resolved.add(resolvePlayerViewOnly(item, tracker));
            }
            return resolved;
        }

        // Other Iterables
        if (obj instanceof Iterable) {
            List<Object> resolved = new ArrayList<Object>();
            for (Object item : (Iterable<Object>) obj) {
                resolved.add(resolvePlayerViewOnly(item, tracker));
            }
            return resolved;
        }

        return obj;
    }

    /**
     * Creates a lightweight SpellAbilityView that preserves display-critical
     * properties (Description, CanPlay, PromptIfOnlyPossibleAbility) but
     * replaces the HostCard with a CardView ID-only stub. This prevents
     * Java serialization from following the full CardView object graph
     * (~14KB per ability), reducing getAbilityToPlay from ~28KB to ~1KB.
     */
    @SuppressWarnings("unchecked")
    private static SpellAbilityView stripSpellAbilityView(final SpellAbilityView sav) {
        SpellAbilityView stub = new SpellAbilityView(sav.getId());
        EnumMap<TrackableProperty, Object> stubProps = stub.getProps();
        EnumMap<TrackableProperty, Object> origProps = sav.getProps();
        // Copy all lightweight properties from original
        if (origProps != null) {
            for (Map.Entry<TrackableProperty, Object> entry : origProps.entrySet()) {
                TrackableProperty key = entry.getKey();
                Object value = entry.getValue();
                if (key == TrackableProperty.HostCard && value instanceof CardView) {
                    // Replace HostCard with ID-only stub
                    stubProps.put(key, new CardView(((CardView) value).getId()));
                } else if (!(value instanceof CardView) && !(value instanceof PlayerView)) {
                    // Copy primitives, Strings, enums — skip any view references
                    stubProps.put(key, value);
                }
            }
        }
        return stub;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveAllViewsInternal(final Object obj, final Tracker tracker) {
        if (obj == null || tracker == null) {
            return obj;
        }

        // Resolve PlayerView stubs
        if (obj instanceof PlayerView) {
            PlayerView resolved = tracker.getObj(TrackableTypes.PlayerViewType, ((PlayerView) obj).getId());
            return resolved != null ? resolved : obj;
        }

        // Resolve CardView stubs — safe because setGameView was just processed
        if (obj instanceof CardView) {
            CardView resolved = tracker.getObj(TrackableTypes.CardViewType, ((CardView) obj).getId());
            if (resolved == null) {
                System.err.println("[ERR] NET RESOLVE: CardView id=" + ((CardView) obj).getId() + " not in tracker");
            }
            return resolved != null ? resolved : obj;
        }

        // Resolve SpellAbilityView — resolve the stub HostCard to real CardView
        if (obj instanceof SpellAbilityView) {
            SpellAbilityView sav = (SpellAbilityView) obj;
            CardView hostCard = sav.getHostCard();
            if (hostCard != null) {
                CardView resolved = tracker.getObj(TrackableTypes.CardViewType, hostCard.getId());
                if (resolved != null) {
                    EnumMap<TrackableProperty, Object> props = sav.getProps();
                    if (props != null) {
                        props.put(TrackableProperty.HostCard, resolved);
                    }
                }
            }
            return obj;
        }

        // Resolve PlayerZoneUpdate — replace inner PlayerView
        if (obj instanceof PlayerZoneUpdate) {
            PlayerZoneUpdate pzu = (PlayerZoneUpdate) obj;
            PlayerView resolved = tracker.getObj(TrackableTypes.PlayerViewType, pzu.getPlayer().getId());
            if (resolved != null) {
                return new PlayerZoneUpdate(resolved, pzu.getZones());
            }
            return obj;
        }

        // Resolve PlayerZoneUpdates
        if (obj instanceof PlayerZoneUpdates) {
            PlayerZoneUpdates resolved = new PlayerZoneUpdates();
            for (PlayerZoneUpdate pzu : (PlayerZoneUpdates) obj) {
                resolved.add((PlayerZoneUpdate) resolveAllViewsInternal(pzu, tracker));
            }
            return resolved;
        }

        // Resolve TrackableCollection
        if (obj instanceof TrackableCollection) {
            TrackableCollection<TrackableObject> original = (TrackableCollection<TrackableObject>) obj;
            TrackableCollection<TrackableObject> resolved = new TrackableCollection<TrackableObject>();
            for (TrackableObject item : original) {
                resolved.add((TrackableObject) resolveAllViewsInternal(item, tracker));
            }
            return resolved;
        }

        // Resolve List
        if (obj instanceof List) {
            List<Object> original = (List<Object>) obj;
            List<Object> resolved = new ArrayList<Object>(original.size());
            for (Object item : original) {
                resolved.add(resolveAllViewsInternal(item, tracker));
            }
            return resolved;
        }

        // Other Iterables
        if (obj instanceof Iterable) {
            List<Object> resolved = new ArrayList<Object>();
            for (Object item : (Iterable<Object>) obj) {
                resolved.add(resolveAllViewsInternal(item, tracker));
            }
            return resolved;
        }

        return obj;
    }
}
