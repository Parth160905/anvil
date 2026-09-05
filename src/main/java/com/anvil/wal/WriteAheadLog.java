package com.anvil.wal;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class WriteAheadLog implements Closeable {

    public static final byte OP_PUT = 0;
    public static final byte OP_DELETE = 1;

    public interface RecordVisitor {
        void onPut(String key, byte[] value);
        void onDelete(String key);
    }

    private final File file;
    private final FileOutputStream fos;
    private final DataOutputStream out;

    public WriteAheadLog(String path) throws IOException {
        this.file = new File(path);
        this.fos = new FileOutputStream(file, true);
        this.out = new DataOutputStream(new BufferedOutputStream(fos));
    }

    public synchronized void logPut(String key, byte[] value) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        out.writeByte(OP_PUT);
        out.writeInt(keyBytes.length);
        out.write(keyBytes);
        out.writeInt(value.length);
        out.write(value);
        flushAndSync();
    }

    public synchronized void logDelete(String key) throws IOException {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        out.writeByte(OP_DELETE);
        out.writeInt(keyBytes.length);
        out.write(keyBytes);
        flushAndSync();
    }

    private void flushAndSync() throws IOException {
        out.flush();
        fos.getFD().sync();
    }

    public void replay(RecordVisitor visitor) throws IOException {
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                byte op;
                try {
                    op = in.readByte();
                } catch (EOFException eof) {
                    break;
                }
                int keyLen = in.readInt();
                byte[] keyBytes = new byte[keyLen];
                in.readFully(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);

                if (op == OP_PUT) {
                    int valLen = in.readInt();
                    byte[] value = new byte[valLen];
                    in.readFully(value);
                    visitor.onPut(key, value);
                } else if (op == OP_DELETE) {
                    visitor.onDelete(key);
                } else {
                    throw new IOException("Corrupt WAL: unknown op byte " + op);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
