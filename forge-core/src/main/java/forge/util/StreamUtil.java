package forge.util;

/**
 * DEPRECATED: Stream API utilities - NOT available on iOS/RoboVM.
 *
 * This class previously contained Stream API utilities that are not compatible
 * with iOS because java.util.stream and java.util.function packages are not
 * available in RoboVM's runtime.
 *
 * Use the following iOS-compatible alternatives:
 * - For random selection: Aggregates.random(iterable) or IterableUtil.random(iterable)
 * - For filtering: IterableUtil.filter(iterable, predicate)
 * - For mapping: IterableUtil.map(iterable, function)
 * - For counting: IterableUtil.count(iterable, predicate)
 */
public class StreamUtil {

    private StreamUtil(){}

    // All Stream-based methods have been removed for iOS compatibility.
    // The java.util.function.* and java.util.stream.* packages are not
    // available on iOS/RoboVM, and having references to them in method
    // signatures causes NoClassDefFoundError at runtime.
    //
    // Use IterableUtil or Aggregates instead:
    // - IterableUtil.random(collection) - picks random element
    // - IterableUtil.random(collection, count) - picks N random elements
    // - IterableUtil.filter(collection, predicate) - filters with forge.util.function.Predicate
    // - Aggregates.random(collection) - alternative random selection
}
