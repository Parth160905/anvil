package com.anvil;

import com.anvil.core.Anvil;

import java.io.IOException;

public class Main {

    private static final String WAL_PATH = "anvil.wal";

    public static void main(String[] args) throws IOException {
        try (Anvil db = new Anvil(WAL_PATH)) {

            if (args.length > 0 && args[0].equals("recover")) {
                System.out.println("--- Recovery check ---");
                System.out.println("name = " + db.getAsString("name"));
                System.out.println("role = " + db.getAsString("role"));
                System.out.println("temp = " + db.getAsString("temp") + " (expect null, was deleted)");
                System.out.println("Recovered " + db.size() + " keys from WAL.");
                return;
            }

            System.out.println("--- Writing ---");
            db.put("name", "Parth");
            db.put("role", "Backend Engineer");
            db.put("temp", "delete me");
            db.delete("temp");

            System.out.println("name = " + db.getAsString("name"));
            System.out.println("role = " + db.getAsString("role"));
            System.out.println("temp = " + db.getAsString("temp") + " (expect null)");

            System.out.println("Run again with argument recover (without deleting anvil.wal) to prove the data survives a fresh process.");
        }
    }
}
