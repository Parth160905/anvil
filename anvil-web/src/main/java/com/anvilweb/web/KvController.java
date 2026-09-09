package com.anvilweb.web;

import com.anvilweb.engine.Anvil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api")
public class KvController {

    private final Anvil anvil;
    private final AtomicLong totalGets = new AtomicLong();
    private final AtomicLong noDiskGets = new AtomicLong();

    public KvController(Anvil anvil) {
        this.anvil = anvil;
    }

    @PutMapping("/kv/{key}")
    public ResponseEntity<?> put(@PathVariable String key, @RequestBody Map<String, String> body) throws IOException {
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing 'value' field"));
        }
        anvil.put(key, value);
        return ResponseEntity.ok(Map.of("key", key, "value", value, "status", "stored"));
    }

    @GetMapping("/kv/{key}")
    public ResponseEntity<?> get(@PathVariable String key) throws IOException {
        Anvil.GetOutcome outcome = anvil.getWithDiagnostics(key);
        totalGets.incrementAndGet();
        if (outcome.sstablesReadFromDisk == 0) {
            noDiskGets.incrementAndGet();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("path", outcome.path);
        body.put("sstablesChecked", outcome.sstablesChecked);
        body.put("sstablesReadFromDisk", outcome.sstablesReadFromDisk);

        if (outcome.value == null) {
            body.put("error", "not found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        body.put("value", new String(outcome.value, StandardCharsets.UTF_8));
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/kv/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) throws IOException {
        anvil.delete(key);
        return ResponseEntity.ok(Map.of("key", key, "status", "deleted"));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        long total = totalGets.get();
        long noDisk = noDiskGets.get();
        double pct = total == 0 ? 0.0 : (100.0 * noDisk / total);
        return ResponseEntity.ok(Map.of(
            "memtableSize", anvil.size(),
            "sstableCount", anvil.sstableCount(),
            "totalLookups", total,
            "noDiskLookups", noDisk,
            "noDiskPercent", Math.round(pct * 10.0) / 10.0
        ));
    }
}
