package com.anvil.core;

import com.anvil.memtable.MemTable;
import com.anvil.sstable.Compactor;
import com.anvil.sstable.SSTable;
import com.anvil.wal.WriteAheadLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Anvil implements AutoCloseable {

    private static final int MEMTABLE_FLUSH_THRESHOLD = 4;
    private static final int COMPACTION_TRIGGER_COUNT = 2; // compact once we hit this many SSTables

    private final String walPath;
    private final File dataDir;
    private MemTable memTable;
    private WriteAheadLog wal;
    private final List<SSTable> sstables = new ArrayList<>(); // oldest first
    private int sstableCounter = 0;

    public Anvil(String walPath, String dataDirPath) throws IOException {
        this.walPath = walPath;
        this.dataDir = new File(dataDirPath);
        if (!dataDir.exists()) dataDir.mkdirs();

        this.memTable = new MemTable();
        this.wal = new WriteAheadLog(walPath);
        loadExistingSSTables();
        recoverFromWal();
    }

    private void loadExistingSSTables() {
        File[] files = dataDir.listFiles((d, name) -> name.startsWith("sstable-") && name.endsWith(".db"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            sstables.add(new SSTable(f));
        }
        for (File f : files) {
            int n = Integer.parseInt(f.getName().replaceAll("\\D", ""));
            sstableCounter = Math.max(sstableCounter, n + 1);
        }
    }

    private void recoverFromWal() throws IOException {
        wal.replay(new WriteAheadLog.RecordVisitor() {
            @Override
            public void onPut(String key, byte[] value) {
                memTable.put(key, value);
            }

            @Override
            public void onDelete(String key) {
                memTable.delete(key);
            }
        });
    }

    public void put(String key, byte[] value) throws IOException {
        wal.logPut(key, value);
        memTable.put(key, value);
        maybeFlush();
    }

    public void put(String key, String value) throws IOException {
        put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public void delete(String key) throws IOException {
        wal.logDelete(key);
        memTable.delete(key);
        maybeFlush();
    }

    public byte[] get(String key) throws IOException {
        byte[] fromMemtable = memTable.get(key);
        if (fromMemtable != null) return fromMemtable;
        if (memTable.isTombstone(key)) return null;

        for (int i = sstables.size() - 1; i >= 0; i--) {
            byte[] result = sstables.get(i).get(key);
            if (result == SSTable.TOMBSTONE_MARKER) return null;
            if (result != null) return result;
        }
        return null;
    }

    public String getAsString(String key) throws IOException {
        byte[] value = get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    public int size() {
        return memTable.size();
    }

    public int sstableCount() {
        return sstables.size();
    }

    private void maybeFlush() throws IOException {
        if (memTable.size() < MEMTABLE_FLUSH_THRESHOLD) return;

        SortedMap<String, byte[]> sorted = new TreeMap<>();
        Set<String> tombstones = new HashSet<>();
        for (Map.Entry<String, byte[]> e : memTable.entries().entrySet()) {
            if (memTable.isTombstone(e.getKey())) {
                tombstones.add(e.getKey());
            }
            sorted.put(e.getKey(), e.getValue());
        }

        File sstFile = new File(dataDir, String.format("sstable-%04d.db", sstableCounter++));
        SSTable.write(sstFile, sorted, tombstones);
        sstables.add(new SSTable(sstFile));
        System.out.println("[flush] wrote " + sstFile.getName() + " with " + sorted.size() + " entries");

        memTable = new MemTable();
        wal.close();
        new File(walPath).delete();
        wal = new WriteAheadLog(walPath);

        maybeCompact();
    }

    private void maybeCompact() throws IOException {
        if (sstables.size() < COMPACTION_TRIGGER_COUNT) return;

        List<SSTable> toMerge = new ArrayList<>(sstables); // oldest first, as stored
        File compactedFile = new File(dataDir, String.format("sstable-%04d.db", sstableCounter++));
        SSTable merged = Compactor.compact(toMerge, compactedFile);

        for (SSTable old : toMerge) {
            old.getFile().delete();
        }
        sstables.clear();
        sstables.add(merged);
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
