package com.anvilweb.web;

import com.anvilweb.engine.Anvil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class KvController {

    private final Anvil anvil;

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
        String value = anvil.getAsString(key);
        if (value == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("key", key, "error", "not found"));
        }
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    @DeleteMapping("/kv/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) throws IOException {
        anvil.delete(key);
        return ResponseEntity.ok(Map.of("key", key, "status", "deleted"));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(Map.of(
            "memtableSize", anvil.size(),
            "sstableCount", anvil.sstableCount()
        ));
    }
}
