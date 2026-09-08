
# anvil

An embedded, LSM-tree-based key-value storage engine written in Java — the kind of engine that sits *underneath* a database, not one with a query language on top. No server, no SQL: you embed it as a library and call `put(key, value)` / `get(key)` / `delete(key)` directly, the same way RocksDB or LevelDB get embedded inside larger systems.

Built in six phases, each independently verified and committed: durable writes, disk-backed storage, compaction, thread-safe concurrent access, JMH-benchmarked performance, and indexed reads (Bloom filters + sparse indexes).

**Live full-stack demo:** [anvil-a0ii.onrender.com](https://anvil-a0ii.onrender.com) — a real Spring Boot + JavaScript UI running the actual engine, not a mock.
**Architecture showcase:** [parth160905.github.io/anvil](https://parth160905.github.io/anvil/) — the diagram and benchmarks below, rendered.

## Architecture

```mermaid
flowchart TD
    Client(["Client: put / get / delete"])

    Client -->|put/delete| WAL["Write-Ahead Log<br/>fsync before ack"]
    WAL --> MemTable["MemTable<br/>ConcurrentSkipListMap"]

    MemTable -->|"size ≥ threshold"| Flush["Flush"]
    Flush --> NewSST["new sstable-NNNN.db"]
    Flush -.clears.-> MemTable
    Flush -.truncates.-> WAL

    NewSST --> CompactCheck{"≥ 2 SSTables?"}
    CompactCheck -->|yes| Compactor["Compactor<br/>merge + drop tombstones"]
    Compactor --> MergedSST["single merged SSTable"]
    CompactCheck -->|no| Idle["wait for next flush"]

    Client -->|get| ReadMem{"key in MemTable?"}
    ReadMem -->|yes| ReturnMem["return value / null if tombstoned"]
    ReadMem -->|no| ReadSST["for each SSTable, newest → oldest"]
    ReadSST --> Bloom{"Bloom filter:<br/>maybe present?"}
    Bloom -->|no| NextTable["skip file entirely, no I/O"]
    Bloom -->|yes| Sparse["sparse index: seek near key"]
    Sparse --> ShortScan["scan ≤16 records from there"]
    ShortScan --> ReturnSST["return first match / null"]
    NextTable --> ReadSST
```

**Write path:** every `put`/`delete` is appended to the WAL and `fsync`'d to disk before the call returns, then applied to an in-memory sorted MemTable. If the process crashes between those two steps, replaying the WAL on restart reconstructs the exact state — no acknowledged write is ever lost.

**Flush:** once the MemTable exceeds a size threshold, it's written out as an immutable, sorted SSTable file, the MemTable is reset, and the WAL is truncated. A Bloom filter and sparse index are built in the same pass and persisted as a `.idx` sidecar next to the `.db` file, so indexing cost is paid once at write time, not on every read.

**Compaction:** once enough SSTables accumulate, they're merged into a single file (with a fresh sidecar of its own). For each key, the newest version wins; tombstoned keys are dropped entirely.

**Reads:** check the MemTable first, then for each SSTable (newest to oldest): consult its Bloom filter to rule the table out with no I/O, otherwise use its sparse index to seek near the key and scan at most ~16 records instead of the whole file. Tables written before this indexing existed self-heal on first open — one scan rebuilds and persists their sidecar.

**Concurrency:** ordinary reads and writes take a shared read lock; only the flush/compaction step takes an exclusive write lock.

## Benchmarks

**Phase 5 baseline**, measured with JMH (3 warmup + 5 measurement iterations, single fork), before any indexing existed:

| Benchmark | Throughput (ops/s) |
|---|---|
| `writeThroughput` | 893.652 ± 202.206 |
| `readFromMemtable` | 2,091.694 ± 95.638 |
| `readFromSSTable` | 88,524.118 ± 502.499 |
| `readMixed` | 4,156.447 ± 6,174.625 |

**Write throughput (~890 ops/s)** is `fsync`-bound by design: every write blocks on a disk sync before acknowledging, trading raw speed for the durability guarantee that a crash never loses an acknowledged write.

**Read throughput varied by roughly 40x depending on where a key physically landed.** This wasn't noise — it was the honest cost of a linear-scan SSTable lookup, and the direct motivation for Phase 6.

Also verified under concurrent load: 8 threads × 500 writes (4,000 total operations) completed in 625ms with zero errors and zero data mismatches on full readback.

**Phase 6 (Bloom filters + sparse indexes)**, measured with JMH the same way:

| Benchmark | Throughput (ops/s) |
|---|---|
| `writeThroughput` | 697.277 ± 242.817 |
| `readFromMemtable` | 15,925.442 ± 883.285 |
| `readFromSSTable` | 69,664.389 ± 2,306.086 |
| `readMixed` | 25,916.809 ± 1,071.718 |
| `readMissingKey` | 10,896,570.641 ± 1,043,402.374 |

The early/late-key spread shrinks from ~40x down to under 4.4x (`readFromSSTable` / `readFromMemtable`). More strikingly, a lookup for a key that doesn't exist — previously the worst case, since it meant scanning every SSTable to the end — is now the *fastest* lookup by nearly two orders of magnitude, rejected by the Bloom filter with zero file I/O. `readFromSSTable` dips slightly (88,524 → 69,664 ops/s): that benchmark hits the single already-optimal case (an early key at the very start of the file), and the new path pays a small fixed cost (a Bloom check plus a `RandomAccessFile` seek) that a raw buffered scan from byte 0 didn't have. `writeThroughput` dips slightly too (893.652 → 697.277 ops/s), since flush now also builds and persists a Bloom filter and sparse index alongside the SSTable file itself.

## Full-stack demo (anvil-web)

The `anvil-web/` folder wraps the core engine in a Spring Boot REST API (`PUT`/`GET`/`DELETE` on `/api/kv/{key}`) with a plain HTML/CSS/JS frontend, deployed live on Render. Every button on the demo page hits real WAL/MemTable/SSTable code running server-side — it's the same engine as above, not a simulation.

Try it: [anvil-a0ii.onrender.com](https://anvil-a0ii.onrender.com) *(free-tier instance — first load after inactivity can take 30-50s to wake up)*

## Project structure
