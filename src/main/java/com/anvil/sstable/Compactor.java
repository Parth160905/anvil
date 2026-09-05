package com.anvil.sstable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Merges a list of SSTables (oldest first) into a single new SSTable.
 * For each key, the version from the NEWEST table wins. Tombstones are
 * dropped entirely in the output — this is the final compaction, so
 * there's no older file left for a tombstone to shadow.
 */
public class Compactor {

    public static SSTable compact(List<SSTable> oldestToNewest, File outputFile) throws IOException {
        // key -> value; a key mapped to null (but present) means "delete it"
        Map<String, byte[]> merged = new LinkedHashMap<>();
        Set<String> deleted = new HashSet<>();

        for (SSTable table : oldestToNewest) {
            table.scanAll((key, value, isTombstone) -> {
                if (isTombstone) {
                    merged.remove(key);
                    deleted.add(key);
                } else {
                    merged.put(key, value);
                    deleted.remove(key);
                }
            });
        }

        SortedMap<String, byte[]> sorted = new TreeMap<>(merged);
        SSTable.write(outputFile, sorted, Collections.emptySet()); // no tombstones survive compaction

        System.out.println("[compact] merged " + oldestToNewest.size() + " tables -> "
                + outputFile.getName() + " (" + sorted.size() + " live keys, "
                + deleted.size() + " tombstones dropped)");

        return new SSTable(outputFile);
    }
}
