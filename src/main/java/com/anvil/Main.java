package com.anvil;

import com.anvil.core.Anvil;

import java.io.IOException;

public class Main {

    private static final String WAL_PATH = "anvil.wal";
    private static final String DATA_DIR = "anvil-data";

    public static void main(String[] args) throws IOException {
        try (Anvil db = new Anvil(WAL_PATH, DATA_DIR)) {

            if (args.length > 0 && args[0].equals("recover")) {
                System.out.println("--- Recovery check ---");
                for (String k : new String[]{"k1","k2","k3","k4","k5","k6","k7","k8","stale"}) {
                    System.out.println(k + " = " + db.getAsString(k));
                }
                System.out.println("SSTables on disk: " + db.sstableCount());
                return;
            }

            System.out.println("--- Writing 8 keys, deleting one, to force flush + compaction ---");
            db.put("k1", "value-one");
            db.put("k2", "value-two");
            db.put("stale", "will be deleted");
            db.put("k3", "value-three");   // flush #1 (4 entries) -> triggers compaction check (1 table, no compact yet)
            db.delete("stale");
            db.put("k4", "value-four");
            db.put("k5", "value-five");
            db.put("k6", "value-six");     // flush #2 (4 entries) -> now 2 tables -> COMPACTION fires
            db.put("k7", "value-seven");
            db.put("k8", "value-eight");

            System.out.println("k1 = " + db.getAsString("k1"));
            System.out.println("stale = " + db.getAsString("stale") + " (expect null, deleted)");
            System.out.println("SSTables on disk: " + db.sstableCount());

            System.out.println("Run again with argument recover to confirm everything survives after compaction.");
        }
    }
}
