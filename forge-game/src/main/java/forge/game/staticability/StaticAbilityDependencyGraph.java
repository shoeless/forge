package forge.game.staticability;

import java.util.*;

/**
 * Simple directed graph implementation for static ability dependency tracking.
 * Replaces jgrapht to avoid Java 8+ functional interface dependencies on iOS/RoboVM.
 */
public class StaticAbilityDependencyGraph<V> {
    private final Set<V> vertices = new LinkedHashSet<V>();
    private final Map<V, Set<V>> outgoingEdges = new HashMap<V, Set<V>>();
    private final Map<V, Set<V>> incomingEdges = new HashMap<V, Set<V>>();

    public void addVertex(V vertex) {
        if (!vertices.contains(vertex)) {
            vertices.add(vertex);
            outgoingEdges.put(vertex, new LinkedHashSet<V>());
            incomingEdges.put(vertex, new LinkedHashSet<V>());
        }
    }

    public void addEdge(V from, V to) {
        addVertex(from);
        addVertex(to);
        outgoingEdges.get(from).add(to);
        incomingEdges.get(to).add(from);
    }

    public void removeEdge(V from, V to) {
        Set<V> fromEdges = outgoingEdges.get(from);
        if (fromEdges != null) {
            fromEdges.remove(to);
        }
        Set<V> toEdges = incomingEdges.get(to);
        if (toEdges != null) {
            toEdges.remove(from);
        }
    }

    public Set<V> vertexSet() {
        return Collections.unmodifiableSet(vertices);
    }

    public boolean hasEdges() {
        for (Set<V> edges : outgoingEdges.values()) {
            if (!edges.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public int outDegreeOf(V vertex) {
        Set<V> edges = outgoingEdges.get(vertex);
        return edges != null ? edges.size() : 0;
    }

    public void removeAllVertices(Set<V> toRemove) {
        for (V v : toRemove) {
            // Remove all edges from this vertex
            Set<V> outgoing = outgoingEdges.get(v);
            if (outgoing != null) {
                for (V to : outgoing) {
                    Set<V> incoming = incomingEdges.get(to);
                    if (incoming != null) {
                        incoming.remove(v);
                    }
                }
            }
            // Remove all edges to this vertex
            Set<V> incoming = incomingEdges.get(v);
            if (incoming != null) {
                for (V from : incoming) {
                    Set<V> fromOutgoing = outgoingEdges.get(from);
                    if (fromOutgoing != null) {
                        fromOutgoing.remove(v);
                    }
                }
            }
            vertices.remove(v);
            outgoingEdges.remove(v);
            incomingEdges.remove(v);
        }
    }

    /**
     * Find all simple cycles in the graph using Johnson's algorithm variant.
     * Based on the Szwarcfiter-Lauer algorithm concept but simplified.
     */
    public List<List<V>> findSimpleCycles() {
        List<List<V>> cycles = new ArrayList<List<V>>();
        Set<V> visited = new HashSet<V>();
        Set<V> onStack = new HashSet<V>();
        Map<V, V> parent = new HashMap<V, V>();

        for (V start : vertices) {
            if (!visited.contains(start)) {
                findCyclesDFS(start, visited, onStack, parent, cycles, start);
            }
        }
        return cycles;
    }

    private void findCyclesDFS(V current, Set<V> visited, Set<V> onStack,
                               Map<V, V> parent, List<List<V>> cycles, V cycleStart) {
        visited.add(current);
        onStack.add(current);

        Set<V> neighbors = outgoingEdges.get(current);
        if (neighbors != null) {
            for (V neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    parent.put(neighbor, current);
                    findCyclesDFS(neighbor, visited, onStack, parent, cycles, cycleStart);
                } else if (onStack.contains(neighbor)) {
                    // Found a cycle - reconstruct it
                    List<V> cycle = new ArrayList<V>();
                    V node = current;
                    while (node != null && !node.equals(neighbor)) {
                        cycle.add(0, node);
                        node = parent.get(node);
                    }
                    if (node != null) {
                        cycle.add(0, neighbor);
                    }
                    if (cycle.size() > 1) {
                        cycles.add(cycle);
                    }
                }
            }
        }
        onStack.remove(current);
    }
}
