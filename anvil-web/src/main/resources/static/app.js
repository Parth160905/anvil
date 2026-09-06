async function refreshStats() {
  const res = await fetch('/api/stats');
  const data = await res.json();
  document.getElementById('stats').textContent =
    `memtable: ${data.memtableSize} · sstables: ${data.sstableCount}`;
}

function show(data) {
  document.getElementById('output').textContent = JSON.stringify(data, null, 2);
}

async function doPut() {
  const key = document.getElementById('putKey').value.trim();
  const value = document.getElementById('putValue').value;
  if (!key) return;
  const res = await fetch(`/api/kv/${encodeURIComponent(key)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ value })
  });
  show(await res.json());
  refreshStats();
}

async function doGet() {
  const key = document.getElementById('getKey').value.trim();
  if (!key) return;
  const res = await fetch(`/api/kv/${encodeURIComponent(key)}`);
  show(await res.json());
}

async function doDelete() {
  const key = document.getElementById('delKey').value.trim();
  if (!key) return;
  const res = await fetch(`/api/kv/${encodeURIComponent(key)}`, { method: 'DELETE' });
  show(await res.json());
  refreshStats();
}

refreshStats();
