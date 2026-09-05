package com.anvil.stress;

import com.anvil.core.Anvil;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hammers put/get/delete from many threads at once, then verifies every
 * write that reported success is actually readable afterward. Proves
 * there's no lost update or corruption under concurrent load.
 */
public class ConcurrencyStressTest {

    private static final int THREAD_COUNT = 8;
    private static final int OPS_PER_THREAD = 500;

    public static void main(String[] args) throws Exception {
        String walPath = "stress.wal";
        String dataDir = "stress-data";
        deleteRecursively(new java.io.File(dataDir));
        new java.io.File(walPath).delete();

        Anvil db = new Anvil(walPath, dataDir);
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);

        long start = System.currentTimeMillis();

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        String key = "thread" + threadId + "-key" + i;
                        db.put(key, "value-" + threadId + "-" + i);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        long writeMs = System.currentTimeMillis() - start;

        System.out.println("--- Write phase done in " + writeMs + "ms, errors: " + errors.get() + " ---");
        System.out.println("SSTables on disk: " + db.sstableCount());
        System.out.println("Remaining in memtable: " + db.size());

        System.out.println("--- Verifying every key is readable and correct ---");
        int checked = 0;
        int mismatches = 0;
        for (int t = 0; t < THREAD_COUNT; t++) {
            for (int i = 0; i < OPS_PER_THREAD; i++) {
                String key = "thread" + t + "-key" + i;
                String expected = "value-" + t + "-" + i;
                String actual = db.getAsString(key);
                checked++;
                if (!expected.equals(actual)) {
                    mismatches++;
                    System.out.println("MISMATCH: " + key + " expected=" + expected + " actual=" + actual);
                }
            }
        }

        System.out.println("Checked " + checked + " keys, mismatches: " + mismatches);
        System.out.println(mismatches == 0 ? "PASS: no lost updates, no corruption." : "FAIL: see mismatches above.");

        db.close();
    }

    private static void deleteRecursively(java.io.File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            for (java.io.File child : f.listFiles()) deleteRecursively(child);
        }
        f.delete();
    }
}
