package com.anvil.sstable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SSTable {

    private final File file;

    public SSTable(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

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

    /** Reads every entry in this file, in order. Used by compaction to merge tables. */
    public void scanAll(EntryVisitor visitor) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                int keyLen;
                try {
                    keyLen = in.readInt();
                } catch (EOFException eof) {
                    return;
                }
                byte[] keyBytes = new byte[keyLen];
                in.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                boolean isTombstone = in.readByte() == 1;

                if (isTombstone) {
                    visitor.onEntry(key, null, true);
                } else {
                    int valLen = in.readInt();
                    byte[] value = new byte[valLen];
                    in.readFully(value);
                    visitor.onEntry(key, value, false);
                }
            }
        }
    }

    public interface EntryVisitor {
        void onEntry(String key, byte[] value, boolean isTombstone);
    }
}
