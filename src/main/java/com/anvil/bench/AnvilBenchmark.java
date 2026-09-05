package com.anvil.bench;

import com.anvil.core.Anvil;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JMH benchmarks for anvil's core operations.
 * Run with: java -jar target/benchmarks.jar
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AnvilBenchmark {

    private Anvil db;
    private Path tempDir;
    private AtomicInteger counter;
    private String[] preloadedKeys;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("anvil-bench");
        String walPath = tempDir.resolve("bench.wal").toString();
        String dataDir = tempDir.resolve("bench-data").toString();
        db = new Anvil(walPath, dataDir);
        counter = new AtomicInteger(0);

        // preload 10,000 keys so read benchmarks have real data to hit
        // (spread across memtable + multiple SSTables, exercising the full read path)
        preloadedKeys = new String[10_000];
        for (int i = 0; i < 10_000; i++) {
            String key = "preload-" + i;
            preloadedKeys[i] = key;
            db.put(key, "value-" + i);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        db.close();
        deleteRecursively(tempDir.toFile());
    }

    @Benchmark
    public void writeThroughput() throws IOException {
        int i = counter.getAndIncrement();
        db.put("write-" + i, "value-" + i);
    }

    @Benchmark
    public void readFromMemtable(Blackhole bh) throws IOException {
        // most recently written keys are still in the memtable
        bh.consume(db.get("preload-9999"));
    }

    @Benchmark
    public void readFromSSTable(Blackhole bh) throws IOException {
        // early keys have been flushed to disk by now
        bh.consume(db.get("preload-0"));
    }

    @Benchmark
    public void readMixed(Blackhole bh) throws IOException {
        int i = counter.getAndIncrement() % preloadedKeys.length;
        bh.consume(db.get(preloadedKeys[i]));
    }

    private static void deleteRecursively(java.io.File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) for (java.io.File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
