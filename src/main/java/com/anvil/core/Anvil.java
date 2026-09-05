package com.anvil.core;

import com.anvil.memtable.MemTable;
import com.anvil.wal.WriteAheadLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class Anvil implements AutoCloseable {

    private final MemTable memTable;
    private final WriteAheadLog wal;

    public Anvil(String walPath) throws IOException {
        this.memTable = new MemTable();
        this.wal = new WriteAheadLog(walPath);
        recover();
    }

    private void recover() throws IOException {
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
    }

    public void put(String key, String value) throws IOException {
        put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] get(String key) {
        return memTable.get(key);
    }

    public String getAsString(String key) {
        byte[] value = get(key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    public void delete(String key) throws IOException {
        wal.logDelete(key);
        memTable.delete(key);
    }

    public int size() {
        return memTable.size();
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }
}
