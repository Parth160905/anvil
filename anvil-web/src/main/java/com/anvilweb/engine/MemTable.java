package com.anvilweb.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public class MemTable {

    private static final byte[] TOMBSTONE = new byte[0];

    private final ConcurrentSkipListMap<String, byte[]> map = new ConcurrentSkipListMap<>();

    public void put(String key, byte[] value) {
        map.put(key, value);
    }

    public void delete(String key) {
        map.put(key, TOMBSTONE);
    }

    public byte[] get(String key) {
        byte[] value = map.get(key);
        return (value == TOMBSTONE) ? null : value;
    }

    public boolean isTombstone(String key) {
        return map.get(key) == TOMBSTONE;
    }

    public int size() {
        return map.size();
    }

    public Map<String, byte[]> entries() {
        return map;
    }
}
