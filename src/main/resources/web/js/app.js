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
  // Compact axis labels: 1234 -> 1.2k, 1200000 -> 1.2M
  const compact = (n) => {
    if (!Number.isFinite(n)) return '—';
    const abs = Math.abs(n);
    if (abs >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (abs >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(Math.round(n * 100) / 100);
  };
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const svgEl = (name, attrs) => {
    const node = document.createElementNS(SVG_NS, name);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    return node;
  };

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
    renderVolumeChart(rows);
    if (!rows.length) { history.textContent = 'No history'; }
    // Builder returns newest-first (ORDER BY date DESC) - show the newest 10
    rows.slice(0, 10).forEach((item) => {
      const row = document.createElement('div'); row.className = 'history-row';
      const date = document.createElement('span'); date.textContent = item.date || '—';
      const volume = document.createElement('strong'); volume.textContent = money(item.transactionVolume ?? item.volume);
      row.append(date, volume); history.append(row);
    });
  }

  // Dependency-free SVG line chart for daily trade volume (S$) - the
  // dashboard stays library-free while dailyHistory finally becomes a
  // chart instead of text rows. Oldest -> newest, left -> right.
  function renderVolumeChart(days) {
    const host = $('volume-chart');
    host.replaceChildren();
    text('chart-range', days.length ? `Last ${days.length} day(s)` : 'No data');
    if (!days.length) {
      host.textContent = 'No chart data yet';
      host.classList.add('muted');
      return;
    }
    host.classList.remove('muted');

    const W = 720, H = 230, PAD_L = 54, PAD_R = 16, PAD_T = 16, PAD_B = 30;
    const series = days.slice().reverse(); // oldest -> newest timeline
    const values = series.map((d) => Number(d.transactionVolume ?? d.volume ?? 0) / 100); // cents -> S$
    const minV = Math.min(...values);
    const maxV = Math.max(...values);
    // A flat series still needs a non-zero span so points land on the baseline
    const span = (maxV - minV) || (maxV || 1);
    const innerW = W - PAD_L - PAD_R, innerH = H - PAD_T - PAD_B;
    const x = (i) => (series.length === 1 ? W / 2 : PAD_L + (i / (series.length - 1)) * innerW);
    const y = (v) => PAD_T + innerH - ((v - minV) / span) * innerH;

    const svg = svgEl('svg', {
      viewBox: `0 0 ${W} ${H}`,
      role: 'img',
      'aria-label': `Daily trade volume in S$ across ${series.length} day(s)`,
    });
    svg.classList.add('chart-svg');

    // Horizontal grid lines + y-axis labels (top / middle / bottom)
    [maxV, (minV + maxV) / 2, minV].forEach((v) => {
      const gy = y(v);
      svg.append(svgEl('line', { x1: PAD_L, x2: W - PAD_R, y1: gy.toFixed(1), y2: gy.toFixed(1), class: 'chart-grid' }));
      const label = svgEl('text', { x: PAD_L - 8, y: (gy + 4).toFixed(1), 'text-anchor': 'end', class: 'chart-text' });
      label.textContent = compact(v);
      svg.append(label);
    });

    const pts = values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`);

    // Area fill under the line, then the line itself, then dots
    svg.append(svgEl('path', {
      d: `M${x(0).toFixed(1)},${H - PAD_B} L${pts.join(' L')} L${x(values.length - 1).toFixed(1)},${H - PAD_B} Z`,
      class: 'chart-area',
    }));
    svg.append(svgEl('polyline', { points: pts.join(' '), class: 'chart-line' }));

    // Per-day visible dot + transparent hover hit-box with a native tooltip
    series.forEach((d, i) => {
      const cx = x(i).toFixed(1), cy = y(values[i]).toFixed(1);
      svg.append(svgEl('circle', { cx, cy, r: 2.6, class: 'chart-dot' }));
      const hit = svgEl('circle', { cx, cy, r: 9, class: 'chart-hit' });
      const tip = document.createElementNS(SVG_NS, 'title');
      tip.textContent = `${d.date || '—'}: ${money(d.transactionVolume ?? d.volume)} S$`;
      hit.append(tip);
      svg.append(hit);
    });

    // X labels: first / middle / last dates (MM-DD from yyyy-MM-dd)
    const idxs = series.length > 2
      ? [0, Math.floor((series.length - 1) / 2), series.length - 1]
      : [...series.keys()];
    idxs.forEach((i) => {
      const anchor = i === 0 ? 'start' : (i === series.length - 1 ? 'end' : 'middle');
      const label = svgEl('text', { x: x(i).toFixed(1), y: H - 8, 'text-anchor': anchor, class: 'chart-text' });
      const date = series[i].date || '';
      label.textContent = date.length >= 10 ? date.slice(5) : date;
      svg.append(label);
    });

    host.append(svg);
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
