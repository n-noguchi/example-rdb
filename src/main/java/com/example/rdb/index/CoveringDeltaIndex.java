package com.example.rdb.index;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * In-memory delta index for post-checkpoint changes.
 * Maintains sorted entries and tombstones for base row invalidation.
 */
public class CoveringDeltaIndex {

    private final ConcurrentSkipListMap<IndexKey, List<CoveringEntry>> entries = new ConcurrentSkipListMap<>();
    private final Set<Long> tombstones = new ConcurrentSkipListSet<>();
    private final Set<Long> overriddenBaseRows = new ConcurrentSkipListSet<>();

    public void insert(CoveringEntry entry) {
        entries.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry);
    }

    public void deleteByRowId(long rowId) {
        tombstones.add(rowId);
        overriddenBaseRows.add(rowId);
        entries.values().forEach(list -> list.removeIf(e -> e.getRowId() == rowId));
        entries.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public void update(long rowId, CoveringEntry newEntry) {
        deleteByRowId(rowId);
        insert(newEntry);
    }

    public boolean isTombstoned(long rowId) {
        return tombstones.contains(rowId);
    }

    public boolean isOverridden(long rowId) {
        return overriddenBaseRows.contains(rowId);
    }

    /**
     * Range scan on delta entries.
     * Returns entries within [lower, upper] (inclusive bounds).
     * Pass null for unbounded lower/upper.
     */
    public List<CoveringEntry> rangeScan(IndexKey lower, boolean lowerInclusive,
                                          IndexKey upper, boolean upperInclusive) {
        NavigableMap<IndexKey, List<CoveringEntry>> subMap;
        if (lower == null && upper == null) {
            subMap = entries;
        } else if (lower == null) {
            subMap = entries.headMap(upper, upperInclusive);
        } else if (upper == null) {
            subMap = entries.tailMap(lower, lowerInclusive);
        } else {
            subMap = entries.subMap(lower, lowerInclusive, upper, upperInclusive);
        }

        List<CoveringEntry> result = new ArrayList<>();
        for (List<CoveringEntry> list : subMap.values()) {
            result.addAll(list);
        }
        return result;
    }

    /**
     * Exact key lookup.
     */
    public List<CoveringEntry> lookup(IndexKey key) {
        List<CoveringEntry> list = entries.get(key);
        return list != null ? new ArrayList<>(list) : List.of();
    }

    /**
     * Prefix lookup: find all entries whose key starts with the given prefix values.
     * Used for composite indexes where only leading key columns are specified.
     */
    public List<CoveringEntry> prefixLookup(IndexKey prefix) {
        List<CoveringEntry> result = new ArrayList<>();
        for (var entry : entries.entrySet()) {
            if (keyStartsWith(entry.getKey(), prefix)) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    private static boolean keyStartsWith(IndexKey key, IndexKey prefix) {
        if (key.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (compareValues(key.get(i), prefix.get(i)) != 0) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Long || b instanceof Long) return Long.compare(na.longValue(), nb.longValue());
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable ca && b.getClass().isInstance(a)) return ca.compareTo(b);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    public void clear() {
        entries.clear();
        tombstones.clear();
        overriddenBaseRows.clear();
    }

    public Set<Long> getTombstones() {
        return tombstones;
    }

    public Set<Long> getOverriddenBaseRows() {
        return overriddenBaseRows;
    }

    public int size() {
        return entries.values().stream().mapToInt(List::size).sum();
    }
}
