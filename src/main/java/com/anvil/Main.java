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
                System.out.println("k1 = " + db.getAsString("k1"));
                System.out.println("k2 = " + db.getAsString("k2"));
                System.out.println("k3 = " + db.getAsString("k3"));
                System.out.println("k4 = " + db.getAsString("k4"));
                System.out.println("k5 = " + db.getAsString("k5"));
                System.out.println("SSTables on disk: " + db.sstableCount());
                return;
            }

            System.out.println("--- Writing 5 keys (flush threshold is 4) ---");
            db.put("k1", "value-one");
            db.put("k2", "value-two");
            db.put("k3", "value-three");
            db.put("k4", "value-four");  // this write should trigger a flush
            db.put("k5", "value-five");  // stays in memtable

            System.out.println("k1 = " + db.getAsString("k1") + " (from SSTable)");
            System.out.println("k5 = " + db.getAsString("k5") + " (from memtable)");
            System.out.println("SSTables on disk: " + db.sstableCount());

            System.out.println("Run again with argument recover to prove SSTable + memtable data both survive a fresh process.");
        }
    }
}
