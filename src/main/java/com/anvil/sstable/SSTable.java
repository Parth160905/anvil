package com.anvil.sstable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * An immutable, sorted, on-disk key-value file. Once written, an SSTable
 * is never modified — only read, and eventually deleted during compaction.
 *
 * File format (repeated for every entry, keys already sorted before write):
 *   [4 byte keyLen] [key bytes] [1 byte tombstoneFlag] [4 byte valLen] [value bytes]
 * valLen/value bytes are omitted when tombstoneFlag == 1.
 */
public class SSTable {

    private final File file;

    public SSTable(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    /** Writes a sorted map of entries to a new SSTable file. */
    public static void write(File file, SortedMap<String, byte[]> entries, Set<String> tombstoneKeys) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String key = e.getKey();
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                boolean isTombstone = tombstoneKeys.contains(key);

                out.writeInt(keyBytes.length);
                out.write(keyBytes);
                out.writeByte(isTombstone ? 1 : 0);

                if (!isTombstone) {
                    byte[] value = e.getValue();
                    out.writeInt(value.length);
                    out.write(value);
                }
            }
        }
    }

    /**
     * Looks up a key by scanning the file linearly. Returns:
     *   - the value bytes, if the key is present with a value
     *   - EMPTY_TOMBSTONE marker, if the key is present but deleted
     *   - null, if the key isn't in this file at all
     * (Linear scan is fine for now — Phase 3/4 can add an index or bloom filter.)
     */
    public static final byte[] TOMBSTONE_MARKER = new byte[0];

    public byte[] get(String targetKey) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                int keyLen;
                try {
                    keyLen = in.readInt();
                } catch (EOFException eof) {
                    return null;
                }
                byte[] keyBytes = new byte[keyLen];
                in.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                boolean isTombstone = in.readByte() == 1;

                if (key.equals(targetKey)) {
                    if (isTombstone) return TOMBSTONE_MARKER;
                    int valLen = in.readInt();
                    byte[] value = new byte[valLen];
                    in.readFully(value);
                    return value;
                } else {
                    if (!isTombstone) {
                        int valLen = in.readInt();
                        in.skipBytes(valLen);
                    }
                }
            }
        }
    }
}
