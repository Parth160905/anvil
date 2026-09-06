package com.anvilweb.engine;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Compactor {

    public static SSTable compact(List<SSTable> oldestToNewest, File outputFile) throws IOException {
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
        SSTable.write(outputFile, sorted, Collections.emptySet());
        return new SSTable(outputFile);
    }
}
