(() => {
  const $ = (id) => document.getElementById(id);
  const text = (id, value) => { $(id).textContent = value ?? '—'; };
  const fmt = (value) => typeof value === 'number' ? value.toLocaleString() : (value ?? '—');
  const percent = (value) => typeof value === 'number' ? `${value.toFixed(2)}%` : (value ?? '—');
  // Contract fix: the Java builder emits every monetary figure as integer CENTS.
  // Convert to S$ for display (previously raw values were shown unconverted and
  // most fields were read under names that never existed in the payload).
  const money = (cents) => typeof cents === 'number'
    ? (cents / 100).toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 })
    : '—';

  function render(data) {
    const live = data.liveMetrics || data.live || {};
    const snapshot = data.latestSnapshot || data.snapshot || {};
    const inflation = data.inflation || {};
    const health = data.healthScore || data.health || {};

    // KPI cards: server-side metrics come from latestSnapshot sections emitted
    // by DashboardDataBuilder (liveMetrics carries no wealth fields).
    text('total-wealth', money(snapshot.totalWealth));
    text('money-supply', money(snapshot.moneySupply));
    text('players', fmt(live.activePlayerCount ?? snapshot.playerCount));
    text('health-score', health.overallScore == null ? '—' : Number(health.overallScore).toFixed(1));
    text('gini', snapshot.giniCoefficient == null ? '—' : Number(snapshot.giniCoefficient).toFixed(4));
    text('inflation', inflation.inflationRate24h == null ? '—' : percent(Number(inflation.inflationRate24h)));
    text('auctions', fmt(snapshot.auctionActiveListings));
    text('auction-value', money(snapshot.auctionTotalValue));
    text('snapshot-time', snapshot.timestamp ? new Date(snapshot.timestamp).toLocaleString() : 'No snapshot');

    const alerts = $('fraud-alerts');
    alerts.replaceChildren();
    const fraud = Array.isArray(data.fraudAlerts) ? data.fraudAlerts : [];
    if (!fraud.length) { alerts.textContent = 'No active alerts'; }
    fraud.slice(0, 8).forEach((item) => {
      const row = document.createElement('div'); row.className = 'list-row';
      const label = document.createElement('span'); label.textContent = item.description || item.type || 'Risk signal';
      const severity = document.createElement('strong'); severity.className = 'bad'; severity.textContent = item.severity || 'ALERT';
      row.append(label, severity); alerts.append(row);
    });

    // Top items: builder emits { bought: [...], sold: [...] } - merge quantities
    // per material so the panel reflects combined trade volume per item.
    const items = $('top-items'); items.replaceChildren();
    const topItemsRaw = data.topItems;
    const merged = new Map();
    const ingestList = (list) => {
      if (!Array.isArray(list)) return;
      list.forEach((it) => {
        const key = it.item || it.material || 'Unknown';
        const qty = Number(it.quantity ?? it.count ?? 0);
        merged.set(key, (merged.get(key) || 0) + (Number.isFinite(qty) ? qty : 0));
      });
    };
    if (topItemsRaw && !Array.isArray(topItemsRaw)) {
      ingestList(topItemsRaw.bought); ingestList(topItemsRaw.sold);
    } else if (Array.isArray(topItemsRaw)) {
      ingestList(topItemsRaw);
    }
    const topEntries = [...merged.entries()].sort((a, b) => b[1] - a[1]);
    if (!topEntries.length) { items.textContent = 'No item data'; }
    topEntries.slice(0, 8).forEach(([material, qty]) => {
      const row = document.createElement('div'); row.className = 'list-row';
      const label = document.createElement('span'); label.textContent = material;
      const count = document.createElement('strong'); count.textContent = fmt(qty);
      row.append(label, count); items.append(row);
    });

    const history = $('history'); history.replaceChildren();
    const rows = Array.isArray(data.dailyHistory || data.history) ? (data.dailyHistory || data.history) : [];
    if (!rows.length) { history.textContent = 'No history'; }
    rows.slice(-10).reverse().forEach((item) => {
      const row = document.createElement('div'); row.className = 'history-row';
      const date = document.createElement('span'); date.textContent = item.date || '—';
      const volume = document.createElement('strong'); volume.textContent = money(item.transactionVolume ?? item.volume);
      row.append(date, volume); history.append(row);
    });
  }

  async function load() {
    try {
      const response = await fetch('/api/data', { headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      render(await response.json());
      $('status').textContent = `Updated ${new Date().toLocaleTimeString()}`;
      $('status').className = 'status good';
    } catch (error) {
      $('status').textContent = 'Dashboard unavailable';
      $('status').className = 'status bad';
      console.warn('Analytics dashboard request failed', error);
    }
  }
  load();
  setInterval(load, 30000);
})();
