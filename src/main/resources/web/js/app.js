(() => {
  const $ = (id) => document.getElementById(id);
  const text = (id, value) => { $(id).textContent = value ?? '—'; };
  const fmt = (value) => typeof value === 'number' ? value.toLocaleString() : (value ?? '—');
  const percent = (value) => typeof value === 'number' ? `${value.toFixed(2)}%` : (value ?? '—');

  function render(data) {
    const live = data.liveMetrics || data.live || {};
    const snapshot = data.latestSnapshot || data.snapshot || {};
    const inflation = data.inflation || {};
    const health = data.healthScore || data.health || {};

    text('total-wealth', fmt(live.totalWealth ?? snapshot.totalWealth));
    text('money-supply', fmt(live.moneySupply ?? snapshot.moneySupply));
    text('players', fmt(live.playerCount ?? snapshot.playerCount));
    text('health-score', health.score == null ? '—' : Number(health.score).toFixed(1));
    text('gini', snapshot.giniCoefficient == null ? '—' : Number(snapshot.giniCoefficient).toFixed(4));
    text('inflation', inflation.rate == null ? '—' : percent(Number(inflation.rate)));
    text('auctions', fmt(snapshot.auctionActiveListings));
    text('auction-value', fmt(snapshot.auctionTotalValue));
    text('snapshot-time', snapshot.timestamp ? new Date(snapshot.timestamp).toLocaleString() : 'No snapshot');

    const alerts = $('fraud-alerts');
    alerts.replaceChildren();
    const fraud = Array.isArray(data.fraudAlerts) ? data.fraudAlerts : [];
    if (!fraud.length) { alerts.textContent = 'No active alerts'; }
    fraud.slice(0, 8).forEach((item) => {
      const row = document.createElement('div'); row.className = 'list-row';
      const label = document.createElement('span'); label.textContent = item.message || item.type || 'Risk signal';
      const severity = document.createElement('strong'); severity.className = 'bad'; severity.textContent = item.severity || 'ALERT';
      row.append(label, severity); alerts.append(row);
    });

    const items = $('top-items'); items.replaceChildren();
    const topItems = Array.isArray(data.topItems) ? data.topItems : [];
    if (!topItems.length) { items.textContent = 'No item data'; }
    topItems.slice(0, 8).forEach((item) => {
      const row = document.createElement('div'); row.className = 'list-row';
      const label = document.createElement('span'); label.textContent = item.material || item.item || 'Unknown item';
      const count = document.createElement('strong'); count.textContent = fmt(item.totalQuantity ?? item.quantity ?? item.count);
      row.append(label, count); items.append(row);
    });

    const history = $('history'); history.replaceChildren();
    const rows = Array.isArray(data.dailyHistory || data.history) ? (data.dailyHistory || data.history) : [];
    if (!rows.length) { history.textContent = 'No history'; }
    rows.slice(-10).reverse().forEach((item) => {
      const row = document.createElement('div'); row.className = 'history-row';
      const date = document.createElement('span'); date.textContent = item.date || '—';
      const volume = document.createElement('strong'); volume.textContent = fmt(item.transactionVolume ?? item.volume);
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
