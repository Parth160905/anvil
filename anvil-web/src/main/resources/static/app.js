function outcomeLabel(path) {
  switch (path) {
    case 'memtable': return 'memtable \u00b7 0 disk reads';
    case 'memtable-tombstone': return 'memtable tombstone \u00b7 0 disk reads';
    case 'bloom-filtered': return 'bloom filter \u00b7 0 disk reads';
    case 'found-on-disk': return 'found on disk';
    case 'found-tombstone': return 'tombstone on disk';
    case 'scanned-not-found': return 'scanned, not found';
    default: return path;
  }
}

async function refreshStats() {
  const res = await fetch('/api/stats');
  const data = await res.json();

  document.getElementById('engineStats').textContent =
    `memtable ${data.memtableSize} keys, ${data.sstableCount} SSTable${data.sstableCount === 1 ? '' : 's'} on disk`;

  const statNumber = document.getElementById('statNumber');
  const statLabel = document.getElementById('statLabel');
  if (data.totalLookups === 0) {
    statNumber.textContent = '\u2014';
    statLabel.textContent = 'no lookups yet \u2014 try Get below';
  } else {
    statNumber.textContent = `${data.noDiskPercent}%`;
    statLabel.textContent = `of ${data.totalLookups} lookup${data.totalLookups === 1 ? '' : 's'} resolved without touching disk`;
  }
}

function show(data) {
  document.getElementById('output').textContent = JSON.stringify(data, null, 2);

  const tag = document.getElementById('outcomeTag');
  if (data.path) {
    tag.textContent = outcomeLabel(data.path);
    tag.hidden = false;
  } else {
    tag.hidden = true;
  }
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
  refreshStats();
}

async function doDelete() {
  const key = document.getElementById('delKey').value.trim();
  if (!key) return;
  const res = await fetch(`/api/kv/${encodeURIComponent(key)}`, { method: 'DELETE' });
  show(await res.json());
  refreshStats();
}

refreshStats();
