package com.anvil.core;

import com.anvil.memtable.MemTable;
import com.anvil.sstable.SSTable;
import com.anvil.wal.WriteAheadLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Phase 2: adds SSTable flushing on top of Phase 1's WAL + MemTable.
 * Once the memtable exceeds MEMTABLE_FLUSH_THRESHOLD entries, it's flushed
 * to an immutable sorted file on disk and the WAL is truncated (that data
 * is now durable on disk, not just in the log). Reads check the memtable
 * first, then SSTables newest-to-oldest.
 */
public class Anvil implements AutoCloseable {

    private static final int MEMTABLE_FLUSH_THRESHOLD = 4; // small on purpose, so you can see flushes happen

    private final String walPath;
    private final File dataDir;
    private MemTable memTable;
    private WriteAheadLog wal;
    private final List<SSTable> sstables = new ArrayList<>(); // newest last
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
            sstableCounter++;
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

        // check SSTables newest to oldest
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

    /** Flushes the memtable to a new SSTable if it's grown past the threshold. */
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

        // reset memtable + WAL: that data is now durable on disk
        memTable = new MemTable();
        wal.close();
        new File(walPath).delete();
        wal = new WriteAheadLog(walPath);
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
