package com.anvilweb.engine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Anvil implements AutoCloseable {

    private static final int MEMTABLE_FLUSH_THRESHOLD = 50;
    private static final int COMPACTION_TRIGGER_COUNT = 3;

    private final String walPath;
    private final File dataDir;
    private volatile MemTable memTable;
    private volatile WriteAheadLog wal;
    private final List<SSTable> sstables = new ArrayList<>();
    private int sstableCounter = 0;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

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
        for (File f : files) sstables.add(new SSTable(f));
        for (File f : files) {
            int n = Integer.parseInt(f.getName().replaceAll("\\D", ""));
            sstableCounter = Math.max(sstableCounter, n + 1);
        }
    }

    private void recoverFromWal() throws IOException {
        wal.replay(new WriteAheadLog.RecordVisitor() {
            @Override public void onPut(String key, byte[] value) { memTable.put(key, value); }
            @Override public void onDelete(String key) { memTable.delete(key); }
        });
    }

    public void put(String key, byte[] value) throws IOException {
        lock.readLock().lock();
        try {
            wal.logPut(key, value);
            memTable.put(key, value);
        } finally {
            lock.readLock().unlock();
        }
        maybeFlush();
    }

    public void put(String key, String value) throws IOException {
        put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public void delete(String key) throws IOException {
        lock.readLock().lock();
        try {
            wal.logDelete(key);
            memTable.delete(key);
        } finally {
            lock.readLock().unlock();
        }
        maybeFlush();
    }

    /** Result of a get(), including WHY it resolved the way it did -- for the demo UI. */
    public static final class GetOutcome {
        public final byte[] value;
        public final String path;
        public final int sstablesChecked;
        public final int sstablesReadFromDisk;

        GetOutcome(byte[] value, String path, int sstablesChecked, int sstablesReadFromDisk) {
            this.value = value;
            this.path = path;
            this.sstablesChecked = sstablesChecked;
            this.sstablesReadFromDisk = sstablesReadFromDisk;
        }
    }

    public GetOutcome getWithDiagnostics(String key) throws IOException {
        lock.readLock().lock();
        MemTable snapshot;
        List<SSTable> tablesSnapshot;
        try {
            snapshot = memTable;
            tablesSnapshot = new ArrayList<>(sstables);
        } finally {
            lock.readLock().unlock();
        }

        byte[] fromMemtable = snapshot.get(key);
        if (fromMemtable != null) {
            return new GetOutcome(fromMemtable, "memtable", tablesSnapshot.size(), 0);
        }
        if (snapshot.isTombstone(key)) {
            return new GetOutcome(null, "memtable-tombstone", tablesSnapshot.size(), 0);
        }

        int diskReads = 0;
        for (int i = tablesSnapshot.size() - 1; i >= 0; i--) {
            SSTable.LookupResult result = tablesSnapshot.get(i).get(key);
            if (result.touchedDisk) diskReads++;
            if (result.value == SSTable.TOMBSTONE_MARKER) {
                return new GetOutcome(null, diskReads == 0 ? "bloom-filtered" : "found-tombstone",
                        tablesSnapshot.size(), diskReads);
            }
            if (result.value != null) {
                return new GetOutcome(result.value, diskReads == 0 ? "bloom-filtered" : "found-on-disk",
                        tablesSnapshot.size(), diskReads);
            }
        }
        return new GetOutcome(null, diskReads == 0 ? "bloom-filtered" : "scanned-not-found",
                tablesSnapshot.size(), diskReads);
    }

    public byte[] get(String key) throws IOException {
        return getWithDiagnostics(key).value;
    }

    public String getAsString(String key) throws IOException {
        byte[] value = get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    public int size() {
        lock.readLock().lock();
        try {
            return memTable.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int sstableCount() {
        lock.readLock().lock();
        try {
            return sstables.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void maybeFlush() throws IOException {
        if (memTable.size() < MEMTABLE_FLUSH_THRESHOLD) return;

        lock.writeLock().lock();
        try {
            if (memTable.size() < MEMTABLE_FLUSH_THRESHOLD) return;

            SortedMap<String, byte[]> sorted = new TreeMap<>();
            Set<String> tombstones = new HashSet<>();
            for (Map.Entry<String, byte[]> e : memTable.entries().entrySet()) {
                if (memTable.isTombstone(e.getKey())) tombstones.add(e.getKey());
                sorted.put(e.getKey(), e.getValue());
            }

            File sstFile = new File(dataDir, String.format("sstable-%04d.db", sstableCounter++));
            SSTable.write(sstFile, sorted, tombstones);
            sstables.add(new SSTable(sstFile));

            memTable = new MemTable();
            wal.close();
            new File(walPath).delete();
            wal = new WriteAheadLog(walPath);

            maybeCompactLocked();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void maybeCompactLocked() throws IOException {
        if (sstables.size() < COMPACTION_TRIGGER_COUNT) return;

        List<SSTable> toMerge = new ArrayList<>(sstables);
        File compactedFile = new File(dataDir, String.format("sstable-%04d.db", sstableCounter++));
        SSTable merged = Compactor.compact(toMerge, compactedFile);

        for (SSTable old : toMerge) old.getFile().delete();
        sstables.clear();
        sstables.add(merged);
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
