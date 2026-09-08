package com.anvilweb.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SSTable {

    private static final double BLOOM_FALSE_POSITIVE_RATE = 0.01;
    private static final int SPARSE_INDEX_INTERVAL = 16;

    public static final byte[] TOMBSTONE_MARKER = new byte[0];

    private final File file;
    private BloomFilter bloomFilter;
    private SparseIndex sparseIndex;

    public SSTable(File file) {
        this.file = file;
        loadOrBuildSidecar();
    }

    public File getFile() {
        return file;
    }

    public static void write(File file, SortedMap<String, byte[]> entries, Set<String> tombstoneKeys) throws IOException {
        BloomFilter bloom = BloomFilter.forExpectedEntries(entries.size(), BLOOM_FALSE_POSITIVE_RATE);
        SparseIndex index = new SparseIndex(SPARSE_INDEX_INTERVAL);

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            long offset = 0;
            int recordNumber = 0;
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                String key = e.getKey();
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                boolean isTombstone = tombstoneKeys.contains(key);

                bloom.add(key);
                index.note(key, offset, recordNumber++);

                out.writeInt(keyBytes.length);
                out.write(keyBytes);
                out.writeByte(isTombstone ? 1 : 0);
                offset += 4L + keyBytes.length + 1;

                if (!isTombstone) {
                    byte[] value = e.getValue();
                    out.writeInt(value.length);
                    out.write(value);
                    offset += 4L + value.length;
                }
            }
        }

        writeSidecarQuietly(sidecarFileFor(file), bloom, index);
    }

    public byte[] get(String targetKey) throws IOException {
        if (bloomFilter != null && !bloomFilter.mightContain(targetKey)) {
            return null;
        }

        long startOffset = 0;
        if (sparseIndex != null) {
            startOffset = sparseIndex.startOffsetFor(targetKey);
            if (startOffset < 0) return null;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(startOffset);
            while (true) {
                int keyLen;
                try {
                    keyLen = raf.readInt();
                } catch (EOFException eof) {
                    return null;
                }
                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                boolean isTombstone = raf.readByte() == 1;

                int cmp = key.compareTo(targetKey);
                if (cmp == 0) {
                    if (isTombstone) return TOMBSTONE_MARKER;
                    int valLen = raf.readInt();
                    byte[] value = new byte[valLen];
                    raf.readFully(value);
                    return value;
                } else if (cmp > 0) {
                    return null;
                } else {
                    if (!isTombstone) {
                        int valLen = raf.readInt();
                        raf.skipBytes(valLen);
                    }
                }
            }
        }
    }

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

    private static File sidecarFileFor(File dataFile) {
        String name = dataFile.getName();
        String base = name.endsWith(".db") ? name.substring(0, name.length() - 3) : name;
        return new File(dataFile.getParentFile(), base + ".idx");
    }

    private void loadOrBuildSidecar() {
        File sidecar = sidecarFileFor(file);
        if (sidecar.exists()) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(sidecar)))) {
                bloomFilter = BloomFilter.readFrom(in);
                sparseIndex = SparseIndex.readFrom(in);
                return;
            } catch (IOException e) {
                // fall through
            }
        }
        rebuildSidecarByScanning(sidecar);
    }

    private void rebuildSidecarByScanning(File sidecar) {
        List<String> keys = new ArrayList<>();
        List<Long> recordOffsets = new ArrayList<>();
        try {
            collectKeysAndOffsets(keys, recordOffsets);
        } catch (IOException e) {
            keys.clear();
            recordOffsets.clear();
        }

        BloomFilter bloom = BloomFilter.forExpectedEntries(Math.max(keys.size(), 1), BLOOM_FALSE_POSITIVE_RATE);
        SparseIndex index = new SparseIndex(SPARSE_INDEX_INTERVAL);
        for (int i = 0; i < keys.size(); i++) {
            bloom.add(keys.get(i));
            index.note(keys.get(i), recordOffsets.get(i), i);
        }

        this.bloomFilter = bloom;
        this.sparseIndex = index;
        writeSidecarQuietly(sidecar, bloom, index);
    }

    private void collectKeysAndOffsets(List<String> keys, List<Long> recordOffsets) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            long offset = 0;
            while (true) {
                long recordStart = offset;
                int keyLen;
                try {
                    keyLen = in.readInt();
                } catch (EOFException eof) {
                    return;
                }
                offset += 4;
                byte[] keyBytes = new byte[keyLen];
                in.readFully(keyBytes);
                offset += keyLen;
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                boolean isTombstone = in.readByte() == 1;
                offset += 1;

                keys.add(key);
                recordOffsets.add(recordStart);

                if (!isTombstone) {
                    int valLen = in.readInt();
                    offset += 4;
                    in.skipBytes(valLen);
                    offset += valLen;
                }
            }
        }
    }

    private static void writeSidecarQuietly(File sidecar, BloomFilter bloom, SparseIndex index) {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(sidecar)))) {
            bloom.writeTo(out);
            index.writeTo(out);
        } catch (IOException e) {
            // sidecar is a cache, safe to skip
        }
    }

    private static final class BloomFilter {

        private final int bitSize;
        private final int hashCount;
        private final BitSet bits;

        private BloomFilter(int bitSize, int hashCount, BitSet bits) {
            this.bitSize = bitSize;
            this.hashCount = hashCount;
            this.bits = bits;
        }

        static BloomFilter forExpectedEntries(int expectedEntries, double falsePositiveRate) {
            int n = Math.max(expectedEntries, 1);
            int m = (int) Math.ceil(-(n * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2)));
            m = Math.max(m, 64);
            int k = Math.max(1, (int) Math.round((m / (double) n) * Math.log(2)));
            return new BloomFilter(m, k, new BitSet(m));
        }

        void add(String key) {
            long h1 = hash(key, 0xC0FFEEL);
            long h2 = hash(key, 0x1234567L);
            for (int i = 0; i < hashCount; i++) {
                bits.set(bitIndex(h1, h2, i));
            }
        }

        boolean mightContain(String key) {
            long h1 = hash(key, 0xC0FFEEL);
            long h2 = hash(key, 0x1234567L);
            for (int i = 0; i < hashCount; i++) {
                if (!bits.get(bitIndex(h1, h2, i))) return false;
            }
            return true;
        }

        private int bitIndex(long h1, long h2, int i) {
            long combined = h1 + (long) i * h2;
            int idx = (int) (combined % bitSize);
            return idx < 0 ? idx + bitSize : idx;
        }

        private static long hash(String key, long seed) {
            byte[] data = key.getBytes(StandardCharsets.UTF_8);
            long h = seed ^ (data.length * 0x9E3779B97F4A7C15L);
            for (byte b : data) {
                h ^= (b & 0xffL);
                h *= 0xFF51AFD7ED558CCDL;
                h ^= (h >>> 33);
            }
            h ^= (h >>> 33);
            h *= 0xC4CEB9FE1A85EC53L;
            h ^= (h >>> 33);
            return h;
        }

        void writeTo(DataOutputStream out) throws IOException {
            out.writeInt(bitSize);
            out.writeInt(hashCount);
            byte[] packed = bits.toByteArray();
            out.writeInt(packed.length);
            out.write(packed);
        }

        static BloomFilter readFrom(DataInputStream in) throws IOException {
            int bitSize = in.readInt();
            int hashCount = in.readInt();
            int packedLen = in.readInt();
            byte[] packed = new byte[packedLen];
            in.readFully(packed);
            return new BloomFilter(bitSize, hashCount, BitSet.valueOf(packed));
        }
    }

    private static final class SparseIndex {

        private final int interval;
        private final TreeMap<String, Long> offsets = new TreeMap<>();

        SparseIndex(int interval) {
            this.interval = interval;
        }

        void note(String key, long offsetOfRecordStart, int recordNumber) {
            if (recordNumber % interval == 0) {
                offsets.put(key, offsetOfRecordStart);
            }
        }

        long startOffsetFor(String targetKey) {
            Map.Entry<String, Long> floor = offsets.floorEntry(targetKey);
            return floor == null ? -1 : floor.getValue();
        }

        void writeTo(DataOutputStream out) throws IOException {
            out.writeInt(interval);
            out.writeInt(offsets.size());
            for (Map.Entry<String, Long> e : offsets.entrySet()) {
                byte[] keyBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeInt(keyBytes.length);
                out.write(keyBytes);
                out.writeLong(e.getValue());
            }
        }

        static SparseIndex readFrom(DataInputStream in) throws IOException {
            int interval = in.readInt();
            SparseIndex idx = new SparseIndex(interval);
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int keyLen = in.readInt();
                byte[] keyBytes = new byte[keyLen];
                in.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                long offset = in.readLong();
                idx.offsets.put(key, offset);
            }
            return idx;
        }
    }
}
