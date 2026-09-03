(() => {
  const $ = (id) => document.getElementById(id);
  const text = (id, value) => { $(id).textContent = value ?? '—'; };
  // Number formatting is pinned to en-US: grouping separators and digit
  // shapes stay identical across browser locales (review feedback: the raw
  // toLocaleString() produced inconsistent separators per visitor).
  const NF = new Intl.NumberFormat('en-US');
  const fmt = (value) => typeof value === 'number' ? NF.format(value) : (value ?? '—');
  const percent = (value) => typeof value === 'number' ? `${value.toFixed(2)}%` : (value ?? '—');
  // Contract fix: the Java builder emits every monetary figure as integer CENTS.
  // Convert to S$ for display.
  const money = (cents) => typeof cents === 'number'
    ? NF.format(cents / 100)
    : '—';
  const compact = (n) => {
    if (!Number.isFinite(n)) return '—';
    const abs = Math.abs(n);
    if (abs >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (abs >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(Math.round(n * 100) / 100);
  };
  const relTime = (ts) => {
    if (!Number.isFinite(ts)) return '';
    const s = Math.max(0, (Date.now() - ts) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
    return `${Math.floor(s / 86400)}d ago`;
  };
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const svgEl = (name, attrs) => {
    const node = document.createElementNS(SVG_NS, name);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    return node;
  };

  // ---------- interaction state (survives re-renders, not reloads) ----------
  const state = {
    range: 30,
    severity: 'ALL',
    alertQuery: '',
    itemQuery: '',
    lastData: null,
  };
  try {
    const saved = localStorage.getItem('solidus-chart-range');
    if (saved === '7' || saved === '14' || saved === '30') state.range = Number(saved);
  } catch (e) { /* storage unavailable: defaults are fine */ }

  // ---------- trend arrows ---------------------------------------------------
  // A trend chip compares a value against a reference. invert=true marks
  // metrics where RISING is bad (gini, inflation) so the color flips.
  function setTrend(id, delta, opts) {
    const el = $(id);
    if (!el) return;
    el.title = '';
    if (delta == null || !Number.isFinite(delta) || delta === 0) {
      if (delta === 0) {
        el.textContent = '→ flat';
        el.className = 'trend trend-flat';
        if (opts && opts.title) el.title = opts.title;
      } else {
        el.textContent = '';
        el.className = 'trend';
        el.dataset.empty = 'true';
      }
      return;
    }
    delete el.dataset.empty;
    const up = delta > 0;
    const good = opts && opts.invert ? !up : up;
    const arrow = up ? '↑' : '↓';
    let label;
    if (opts && opts.unit === 'money') {
      label = `${arrow} ${up ? '+' : '−'}S$${NF.format(Math.abs(delta / 100))}`;
    } else if (opts && opts.unit === 'count') {
      label = `${arrow} ${up ? '+' : '−'}${NF.format(Math.abs(delta))}`;
    } else {
      label = `${arrow} ${up ? '+' : '−'}${Math.abs(delta).toFixed(opts && opts.digits ? opts.digits : 2)}`;
    }
    if (opts && opts.suffix) label += opts.suffix;
    el.textContent = label;
    el.className = `trend ${good ? 'trend-up' : 'trend-down'}`;
    if (opts && opts.title) el.title = opts.title;
  }

  function render(data) {
    state.lastData = data;
    const live = data.liveMetrics || data.live || {};
    const snapshot = data.latestSnapshot || data.snapshot || {};
    const inflation = data.inflation || {};
    const health = data.healthScore || data.health || {};
    const trend = data.snapshotTrend || null;
    const wealth = data.wealthDistribution || null;

    // KPI cards
    text('total-wealth', money(snapshot.totalWealth));
    text('money-supply', money(snapshot.moneySupply));
    text('players', fmt(live.activePlayerCount ?? snapshot.playerCount));
    text('health-score', health.overallScore == null ? '—' : Number(health.overallScore).toFixed(1));
    const gradeChip = $('health-grade-chip');
    if (gradeChip) {
      if (health.grade) {
        gradeChip.textContent = `Grade ${health.grade}`;
        gradeChip.className = 'trend trend-grade';
        delete gradeChip.dataset.empty;
      } else {
        gradeChip.textContent = '';
        gradeChip.className = 'trend';
        gradeChip.dataset.empty = 'true';
      }
    }
    // KPI trends: wealth/supply move against the previous snapshot; players
    // against yesterday's active count; inflation compares 24h vs 7d.
    setTrend('trend-wealth', trend ? trend.totalWealthDelta : null, {
      unit: 'money', title: 'Change since previous snapshot',
    });
    setTrend('trend-supply', trend ? trend.moneySupplyDelta : null, {
      unit: 'money', title: 'Change since previous snapshot',
    });
    const hist = rows();
    const yesterday = hist.length > 1 ? hist[1] : null;
    setTrend('trend-players', yesterday && yesterday.activePlayers != null && live.activePlayerCount != null
      ? live.activePlayerCount - yesterday.activePlayers : null, {
      unit: 'count', title: 'Active now vs yesterday',
    });
    if (inflation.inflationRate24h != null && inflation.inflationRate7d != null) {
      setTrend('trend-inflation', Number(inflation.inflationRate24h) - Number(inflation.inflationRate7d), {
        invert: true, digits: 2, suffix: ' vs 7d', title: '24h rate minus 7d rate (percentage points)',
      });
    } else {
      setTrend('trend-inflation', null, {});
    }

    // Snapshot panel + age-aware freshness
    const snapAge = snapshot.timestamp ? Math.floor((Date.now() - snapshot.timestamp) / 60000) : null;
    const snapLabel = snapshot.timestamp
      ? `${new Date(snapshot.timestamp).toLocaleString()}${snapAge != null ? ` (${snapAge === 0 ? '<1m' : snapAge + 'm old'})` : ''}`
      : 'No snapshot';
    const snapEl = $('snapshot-time');
    text('snapshot-time', snapLabel);
    if (snapEl) {
      snapEl.classList.toggle('stale', snapAge != null && snapAge > 60);
      snapEl.classList.toggle('fresh', snapAge != null && snapAge <= 60);
    }
    text('gini', snapshot.giniCoefficient == null ? '—' : Number(snapshot.giniCoefficient).toFixed(4));
    text('inflation', inflation.inflationRate24h == null ? '—' : percent(Number(inflation.inflationRate24h)));
    text('avg-balance', money(snapshot.avgBalance));
    text('median-balance', money(snapshot.medianBalance));
    text('auctions', fmt(snapshot.auctionActiveListings));
    text('auction-value', money(snapshot.auctionTotalValue));
    setTrend('trend-gini', trend ? trend.giniDelta : null, {
      invert: true, digits: 4, title: 'Change since previous snapshot',
    });
    setTrend('trend-auctions', trend ? trend.auctionListingsDelta : null, {
      unit: 'count', title: 'Change since previous snapshot',
    });

    renderHealth(health);
    renderAlerts(data);
    renderItems(data);
    renderWealth(wealth);
    renderVolumeChart(hist.slice(0, state.range));
    renderHistory(hist.slice(0, state.range));

    // Today's volume vs yesterday's full day (only meaningful once a
    // yesterday row exists)
    const todayRow = hist.length > 0 ? hist[0] : null;
    const todayVol = live.dailyVolume != null ? live.dailyVolume : (todayRow ? todayRow.transactionVolume : null);
    setTrend('volume-trend', todayVol != null && yesterday && yesterday.transactionVolume
      ? (todayVol - yesterday.transactionVolume) / 100 : null, {
      unit: 'money', title: "Today's volume vs yesterday's full day",
    });
  }

  function renderHealth(health) {
    const host = $('health-body');
    const note = $('health-note');
    host.replaceChildren();
    if (health == null || health.overallScore == null) {
      note.textContent = '';
      const notice = document.createElement('div');
      notice.className = 'premium-note';
      notice.textContent = 'Health scoring is a premium feature. Activate a Solidus license to unlock the composite score and its five components.';
      host.append(notice);
      return;
    }
    note.textContent = health.grade ? `Grade ${health.grade}` : '';
    const components = [
      ['Gini', health.giniScore], ['Inflation', health.inflationScore],
      ['Money growth', health.moneyGrowthScore], ['Activity', health.activityScore],
      ['Liquidity', health.liquidityScore],
    ];
    components.forEach(([label, score]) => {
      const row = document.createElement('div');
      row.className = 'score-row';
      const name = document.createElement('span');
      name.className = 'score-label';
      name.textContent = label;
      const track = document.createElement('span');
      track.className = 'bar-track';
      const fill = document.createElement('span');
      fill.className = 'bar-fill';
      const v = Number(score);
      if (Number.isFinite(v)) {
        fill.style.width = `${Math.max(3, Math.min(100, Math.round(v)))}%`;
        fill.classList.add(v >= 70 ? 'fill-good' : v >= 40 ? 'fill-warn' : 'fill-bad');
      }
      track.append(fill);
      const val = document.createElement('strong');
      val.className = 'score-value';
      val.textContent = Number.isFinite(v) ? v.toFixed(0) : '—';
      row.append(name, track, val);
      host.append(row);
    });
    if (health.summary) {
      const summary = document.createElement('p');
      summary.className = 'health-summary';
      summary.textContent = health.summary;
      host.append(summary);
    }
  }

  // ---------- alerts (search + severity filter + copy) -----------------------
  function renderAlerts(data) {
    const host = $('fraud-alerts');
    host.replaceChildren();
    const fraud = Array.isArray(data.fraudAlerts) ? data.fraudAlerts : [];
    const q = state.alertQuery.trim().toLowerCase();
    const filtered = fraud.filter((item) => {
      if (state.severity !== 'ALL' && String(item.severity || '').toUpperCase() !== state.severity) return false;
      if (!q) return true;
      const hay = `${item.playerName || ''} ${item.type || ''} ${item.description || ''}`.toLowerCase();
      return hay.includes(q);
    });
    const shown = filtered.slice(0, 30);
    $('alert-count').textContent = fraud.length
      ? `${shown.length} shown / ${filtered.length} match${fraud.length !== filtered.length ? ` of ${fraud.length}` : ''}`
      : '';
    if (!fraud.length) {
      host.textContent = 'No active alerts';
      host.classList.add('muted');
      return;
    }
    host.classList.remove('muted');
    if (!shown.length) {
      const empty = document.createElement('div');
      empty.className = 'muted';
      empty.textContent = 'No alerts match the current filter';
      host.append(empty);
      return;
    }
    shown.forEach((item) => {
      const row = document.createElement('div');
      row.className = 'alert-row';
      const main = document.createElement('div');
      main.className = 'alert-main';
      const title = document.createElement('span');
      title.className = 'alert-title';
      title.textContent = `${String(item.type || 'RISK').replace(/_/g, ' ')} · ${item.playerName || 'unknown'}`;
      const desc = document.createElement('span');
      desc.className = 'alert-desc';
      desc.textContent = item.description || item.type || 'Risk signal';
      desc.title = desc.textContent;
      main.append(title, desc);
      const chip = document.createElement('span');
      chip.className = 'chip ' + severityClass(item.severity);
      chip.textContent = item.severity || 'ALERT';
      const when = document.createElement('span');
      when.className = 'time-ago';
      when.textContent = relTime(item.timestamp);
      when.title = item.timestamp ? new Date(item.timestamp).toLocaleString() : '';
      const copy = document.createElement('button');
      copy.className = 'copy-btn';
      copy.type = 'button';
      copy.textContent = 'Copy';
      copy.title = 'Copy this alert to the clipboard';
      const payload = `[${item.severity || 'ALERT'}] ${item.type || 'RISK'} — ${item.playerName || 'unknown'}: ${item.description || ''}`;
      copy.addEventListener('click', () => copyText(payload, copy));
      row.append(main, chip, when, copy);
      host.append(row);
    });
  }

  function severityClass(severity) {
    switch (String(severity || '').toUpperCase()) {
      case 'HIGH': return 'chip-bad';
      case 'MEDIUM': return 'chip-warn';
      case 'LOW': return 'chip-muted';
      default: return 'chip-muted';
    }
  }

  // ---------- top items (search) ----------------------------------------------
  function renderItems(data) {
    const host = $('top-items');
    host.replaceChildren();
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
    const q = state.itemQuery.trim().toLowerCase();
    const entries = [...merged.entries()].sort((a, b) => b[1] - a[1]);
    const filtered = q ? entries.filter(([material]) => material.toLowerCase().includes(q)) : entries;
    const limit = q ? 24 : 8;
    $('items-note').textContent = entries.length
      ? (q ? `${filtered.length}/${entries.length} items` : `top ${Math.min(limit, entries.length)} of ${entries.length}`)
      : '';
    if (!filtered.length) {
      host.textContent = q ? 'No item matches the search' : 'No item data';
      host.classList.add('muted');
      return;
    }
    host.classList.remove('muted');
    const maxQty = filtered[0][1];
    filtered.slice(0, limit).forEach(([material, qty], idx) => {
      const row = document.createElement('div'); row.className = 'item-row';
      const rank = document.createElement('span'); rank.className = 'rank' + (idx === 0 && !q ? ' rank-1' : '');
      rank.textContent = String(idx + 1);
      const label = document.createElement('span'); label.className = 'row-label';
      label.textContent = material;
      const track = document.createElement('span'); track.className = 'bar-track';
      const fill = document.createElement('span'); fill.className = 'bar-fill';
      fill.style.width = maxQty > 0 ? `${Math.max(4, Math.round((qty / maxQty) * 100))}%` : '4%';
      track.append(fill);
      const count = document.createElement('span'); count.className = 'item-qty';
      count.textContent = fmt(qty);
      row.append(rank, label, track, count); host.append(row);
    });
  }

  // ---------- wealth distribution (donut + richest players) -------------------
  function renderWealth(wealth) {
    const host = $('wealth-body');
    host.replaceChildren();
    const note = $('wealth-note');
    if (!wealth || !Number.isFinite(wealth.playerCount) || wealth.playerCount <= 0) {
      note.textContent = '';
      const empty = document.createElement('div');
      empty.className = 'wealth-empty muted';
      empty.textContent = 'Wealth data appears once the economy database has players.';
      host.append(empty);
      return;
    }
    note.textContent = `${fmt(wealth.playerCount)} players · live`;
    const wrap = document.createElement('div');
    wrap.className = 'wealth-wrap';
    wrap.append(buildDonut(wealth));
    wrap.append(buildRichList(wealth));
    host.append(wrap);
  }

  function buildDonut(wealth) {
    const box = document.createElement('div');
    box.className = 'donut-box';
    const size = 168, cx = size / 2, cy = size / 2, r = 62;
    const svg = svgEl('svg', { viewBox: `0 0 ${size} ${size}`, role: 'img',
      'aria-label': `Wealth distribution: top 1% holds ${(wealth.top1Share * 100).toFixed(1)}%, top 10% holds ${(wealth.top10Share * 100).toFixed(1)}%` });
    const segs = [
      { label: 'Top 1%', share: wealth.top1Share, cls: 'donut-seg-top1' },
      { label: 'Top 2–10%', share: Math.max(0, wealth.top10Share - wealth.top1Share), cls: 'donut-seg-top10' },
      { label: 'Everyone else', share: Math.max(0, 1 - wealth.top10Share), cls: 'donut-seg-rest' },
    ].filter((s) => Number.isFinite(s.share) && s.share > 0);
    let angle = -90;
    segs.forEach((seg) => {
      const sweep = seg.share * 360;
      const path = arcPath(cx, cy, r, angle, angle + Math.min(sweep, 359.99));
      path.setAttribute('class', `donut-arc ${seg.cls}`);
      const title = document.createElementNS(SVG_NS, 'title');
      title.textContent = `${seg.label}: ${(seg.share * 100).toFixed(1)}%`;
      path.append(title);
      svg.append(path);
      angle += sweep;
    });
    const centerVal = svgEl('text', { x: cx, y: cy - 2, 'text-anchor': 'middle', class: 'donut-center-val' });
    centerVal.textContent = `${(wealth.top1Share * 100).toFixed(1)}%`;
    const centerLabel = svgEl('text', { x: cx, y: cy + 16, 'text-anchor': 'middle', class: 'donut-center-label' });
    centerLabel.textContent = 'held by top 1%';
    svg.append(centerVal, centerLabel);
    box.append(svg);
    const legend = document.createElement('div');
    legend.className = 'donut-legend';
    segs.forEach((seg) => {
      const row = document.createElement('div');
      row.className = 'legend-row';
      const swatch = document.createElement('span');
      swatch.className = `swatch ${seg.cls}`;
      const label = document.createElement('span');
      label.className = 'legend-label';
      label.textContent = seg.label;
      const value = document.createElement('strong');
      value.textContent = `${(seg.share * 100).toFixed(1)}%`;
      row.append(swatch, label, value);
      legend.append(row);
    });
    box.append(legend);
    return box;
  }

  function arcPath(cx, cy, r, a0, a1) {
    const rad = (deg) => (deg * Math.PI) / 180;
    const p0 = [cx + r * Math.cos(rad(a0)), cy + r * Math.sin(rad(a0))];
    const p1 = [cx + r * Math.cos(rad(a1)), cy + r * Math.sin(rad(a1))];
    const large = a1 - a0 > 180 ? 1 : 0;
    return svgEl('path', {
      d: `M ${p0[0].toFixed(2)} ${p0[1].toFixed(2)} A ${r} ${r} 0 ${large} 1 ${p1[0].toFixed(2)} ${p1[1].toFixed(2)}`,
    });
  }

  function buildRichList(wealth) {
    const list = document.createElement('div');
    list.className = 'rich-list';
    const head = document.createElement('div');
    head.className = 'rich-head';
    head.textContent = 'Richest players';
    list.append(head);
    const players = Array.isArray(wealth.topPlayers) ? wealth.topPlayers : [];
    const leaderShare = players.length ? Number(players[0].share) || 0 : 0;
    players.forEach((p) => {
      const row = document.createElement('div');
      row.className = 'rich-row';
      const rank = document.createElement('span');
      rank.className = 'rank' + (p.rank === 1 ? ' rank-1' : '');
      rank.textContent = String(p.rank);
      const name = document.createElement('span');
      name.className = 'row-label';
      name.textContent = p.name || 'unknown';
      name.title = p.name || 'unknown';
      const track = document.createElement('span');
      track.className = 'bar-track';
      const fill = document.createElement('span');
      fill.className = 'bar-fill';
      const share = Number(p.share);
      if (leaderShare > 0 && Number.isFinite(share)) {
        fill.style.width = `${Math.max(4, Math.round((share / leaderShare) * 100))}%`;
      }
      track.append(fill);
      const balance = document.createElement('strong');
      balance.className = 'rich-balance';
      balance.textContent = money(p.balance);
      const shareEl = document.createElement('span');
      shareEl.className = 'rich-share';
      shareEl.textContent = Number.isFinite(share) ? `${(share * 100).toFixed(1)}%` : '—';
      row.append(rank, name, track, balance, shareEl);
      list.append(row);
    });
    return list;
  }

  function rows() {
    const raw = state.lastData
      ? (state.lastData.dailyHistory || state.lastData.history)
      : [];
    return Array.isArray(raw) ? raw : [];
  }

  // ---------- history rows (follows the chart range) ---------------------------
  function renderHistory(slice) {
    const history = $('history');
    history.replaceChildren();
    $('history-note').textContent = slice.length ? `last ${slice.length} day(s)` : '';
    if (!slice.length) { history.textContent = 'No history'; history.classList.add('muted'); return; }
    history.classList.remove('muted');
    const maxVol = Math.max(0, ...slice.map((item) => Number(item.transactionVolume ?? item.volume ?? 0)));
    slice.forEach((item) => {
      const row = document.createElement('div'); row.className = 'history-row';
      const date = document.createElement('span'); date.className = 'muted';
      date.textContent = item.date || '—';
      const track = document.createElement('span'); track.className = 'bar-track';
      const fill = document.createElement('span'); fill.className = 'bar-fill bar-fill-soft';
      const vol = Number(item.transactionVolume ?? item.volume ?? 0);
      fill.style.width = maxVol > 0 ? `${Math.max(4, Math.round((vol / maxVol) * 100))}%` : '4%';
      track.append(fill);
      const volume = document.createElement('strong'); volume.textContent = money(item.transactionVolume ?? item.volume);
      const count = document.createElement('span'); count.className = 'muted history-count';
      count.textContent = `${fmt(item.transactionCount)} tx · ${fmt(item.activePlayers)} players`;
      row.append(date, track, volume, count); history.append(row);
    });
  }

  // ---------- volume chart (hover readout + average + ranges) ------------------
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
    host.append(buildChartSvg(days, { W: 720, H: 230 }));
  }

  function buildChartSvg(days, size) {
    const { W, H } = size;
    const PAD_L = 54, PAD_R = 16, PAD_T = 16, PAD_B = 30;
    const series = days.slice().reverse(); // oldest -> newest
    const values = series.map((d) => Number(d.transactionVolume ?? d.volume ?? 0) / 100); // cents -> S$
    const minV = Math.min(...values);
    const maxV = Math.max(...values);
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

    [maxV, (minV + maxV) / 2, minV].forEach((v) => {
      const gy = y(v);
      svg.append(svgEl('line', { x1: PAD_L, x2: W - PAD_R, y1: gy.toFixed(1), y2: gy.toFixed(1), class: 'chart-grid' }));
      const label = svgEl('text', { x: PAD_L - 8, y: (gy + 4).toFixed(1), 'text-anchor': 'end', class: 'chart-text' });
      label.textContent = compact(v);
      svg.append(label);
    });

    // Average reference line: the flat dashed baseline a manager can compare
    // every day against (review feedback: absolute numbers need context).
    const avgV = values.reduce((a, b) => a + b, 0) / values.length;
    const avgY = y(avgV);
    svg.append(svgEl('line', { x1: PAD_L, x2: W - PAD_R, y1: avgY.toFixed(1), y2: avgY.toFixed(1), class: 'chart-avg' }));
    const avgLabel = svgEl('text', { x: W - PAD_R, y: (avgY - 6).toFixed(1), 'text-anchor': 'end', class: 'chart-text chart-avg-text' });
    avgLabel.textContent = `avg ${compact(avgV)}`;
    svg.append(avgLabel);

    const pts = values.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`);
    svg.append(svgEl('path', {
      d: `M${x(0).toFixed(1)},${H - PAD_B} L${pts.join(' L')} L${x(values.length - 1).toFixed(1)},${H - PAD_B} Z`,
      class: 'chart-area',
    }));
    svg.append(svgEl('polyline', { points: pts.join(' '), class: 'chart-line' }));

    // hover apparatus: crosshair + focus dot, positioned by pointer events
    const cross = svgEl('line', { x1: 0, x2: 0, y1: PAD_T, y2: H - PAD_B, class: 'chart-cross' });
    const focus = svgEl('circle', { cx: 0, cy: 0, r: 5, class: 'chart-focus' });
    cross.style.display = 'none';
    focus.style.display = 'none';

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

    svg.append(cross, focus);

    // per-day hit zones drive the readout; native <title> stays as the
    // touch/keyboard fallback
    series.forEach((d, i) => {
      const cx = x(i).toFixed(1), cy = y(values[i]).toFixed(1);
      svg.append(svgEl('circle', { cx, cy, r: 2.6, class: 'chart-dot' }));
      const hit = svgEl('circle', { cx, cy, r: 11, class: 'chart-hit', tabindex: '-1' });
      const tip = document.createElementNS(SVG_NS, 'title');
      tip.textContent = `${d.date || '—'}: ${money(d.transactionVolume ?? d.volume)} S$`;
      hit.append(tip);
      hit.addEventListener('pointerenter', () => {
        cross.setAttribute('x1', cx); cross.setAttribute('x2', cx);
        cross.style.display = '';
        focus.setAttribute('cx', cx); focus.setAttribute('cy', cy);
        focus.style.display = '';
      });
      hit.addEventListener('pointerleave', () => {
        cross.style.display = 'none';
        focus.style.display = 'none';
      });
      hit.addEventListener('focus', () => {
        cross.setAttribute('x1', cx); cross.setAttribute('x2', cx);
        cross.style.display = '';
        focus.setAttribute('cx', cx); focus.setAttribute('cy', cy);
        focus.style.display = '';
      });
      hit.addEventListener('blur', () => {
        cross.style.display = 'none';
        focus.style.display = 'none';
      });
      svg.append(hit);
    });
    return svg;
  }

  // ---------- CSV export --------------------------------------------------------
  // Audit 4 / F-1 — spreadsheet formula injection guard (core 2.1.3
  // TransactionLog.csvEscape parity): a cell that BEGINS with =, +, -, @,
  // TAB or CR is passed through unquoted-and-executed by Excel/LibreOffice
  // when an admin opens the export. Player-controlled fields (names from
  // offline-mode servers, imported economy.db rows, alert descriptions)
  // must never reach a privileged consumer as executable content, so such
  // cells get a leading apostrophe — the standard neutralizing prefix.
  const FORMULA_PREFIXES = new Set(['=', '+', '-', '@', '\t', '\r']);
  const csvCell = (value) => {
    const s = value == null ? '' : String(value);
    const needsQuoting = /[",\n\r]/.test(s);
    const formulaPrefix = s.length > 0 && FORMULA_PREFIXES.has(s[0]);
    if (!needsQuoting && !formulaPrefix) return s;
    const escaped = formulaPrefix ? `'${s}` : s;
    return needsQuoting ? `"${escaped.replace(/"/g, '""')}"` : escaped;
  };
  // round floats to a stable precision: 0.16599999999999998 -> 0.166 keeps
  // the file machine-parsable without floating-point artifacts
  const csvNum = (value, digits = 6) =>
    typeof value === 'number' && Number.isFinite(value) ? Number(value.toFixed(digits)) : '';
  const csvMoney = (cents) => (typeof cents === 'number' ? Math.round(cents) / 100 : '');

  function exportCsv() {
    const data = state.lastData;
    if (!data) return;
    const lines = [];
    const row = (...cells) => lines.push(cells.map(csvCell).join(','));
    row('Solidus Analytics export');
    row('Generated', new Date().toISOString());
    if (data.server && data.server.name) row('Server', data.server.name);
    lines.push('');
    const live = data.liveMetrics || {};
    const snapshot = data.latestSnapshot || {};
    const inflation = data.inflation || {};
    const health = data.healthScore || {};
    const wealth = data.wealthDistribution || null;
    row('Summary', 'Value', 'Unit');
    row('Total wealth', csvMoney(snapshot.totalWealth), 'S$');
    row('Money supply', csvMoney(snapshot.moneySupply), 'S$');
    row('Registered players', snapshot.playerCount, 'count');
    row('Active players', live.activePlayerCount, 'count');
    row('Daily volume', csvMoney(live.dailyVolume), 'S$');
    row('Daily transactions', live.dailyTransactionCount, 'count');
    row('Gini coefficient', csvNum(snapshot.giniCoefficient), 'index');
    row('Top 1% share', csvNum(snapshot.top1PercentShare), 'fraction');
    row('Inflation 24h', csvNum(inflation.inflationRate24h, 4), '%');
    row('Inflation 7d', csvNum(inflation.inflationRate7d, 4), '%');
    row('Inflation 30d', csvNum(inflation.inflationRate30d, 4), '%');
    row('Health score', csvNum(health.overallScore, 2), '/100');
    row('Active auctions', snapshot.auctionActiveListings, 'count');
    row('Auction value', csvMoney(snapshot.auctionTotalValue), 'S$');
    if (snapshot.timestamp) row('Snapshot time', new Date(snapshot.timestamp).toISOString());
    lines.push('');
    if (wealth && Array.isArray(wealth.topPlayers) && wealth.topPlayers.length) {
      row('Wealth distribution (live)');
      row('Rank', 'Player', 'Balance', 'Share');
      wealth.topPlayers.forEach((p) => row(p.rank, p.name, csvMoney(p.balance), csvNum(p.share)));
      row('Top 1% share', csvNum(wealth.top1Share));
      row('Top 10% share', csvNum(wealth.top10Share));
      lines.push('');
    }
    const hist = rows();
    if (hist.length) {
      row('Daily history');
      row('Date', 'Transactions', 'Volume', 'Active players', 'Inflation %');
      hist.forEach((d) => row(d.date, d.transactionCount, csvMoney(d.transactionVolume), d.activePlayers, csvNum(d.inflationRate, 4)));
      lines.push('');
    }
    if (Array.isArray(data.fraudAlerts) && data.fraudAlerts.length) {
      row('Risk alerts');
      row('Time', 'Severity', 'Type', 'Player', 'Description');
      data.fraudAlerts.forEach((a) => row(
        a.timestamp ? new Date(a.timestamp).toISOString() : '', a.severity, a.type, a.playerName, a.description));
      lines.push('');
    }
    const merged = new Map();
    const ingest = (list) => Array.isArray(list) && list.forEach((it) => {
      const key = it.item || it.material || 'Unknown';
      merged.set(key, (merged.get(key) || 0) + (Number(it.quantity ?? 0) || 0));
    });
    if (data.topItems) { ingest(data.topItems.bought); ingest(data.topItems.sold); }
    if (merged.size) {
      row('Top items');
      row('Item', 'Combined quantity');
      [...merged.entries()].sort((a, b) => b[1] - a[1]).forEach(([k, v]) => row(k, v));
    }
    const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
    a.href = url;
    a.download = `solidus-analytics-${stamp}.csv`;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
  }

  // ---------- clipboard -----------------------------------------------------------
  async function copyText(value, button) {
    let ok = false;
    try {
      await navigator.clipboard.writeText(value);
      ok = true;
    } catch (e) {
      // fallback for browsers/permissions without the async clipboard API
      try {
        const ta = document.createElement('textarea');
        ta.value = value;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.append(ta);
        ta.select();
        ok = document.execCommand('copy');
        ta.remove();
      } catch (e2) {
        ok = false;
      }
    }
    if (button) {
      const original = button.textContent;
      button.textContent = ok ? 'Copied' : 'Failed';
      button.classList.add(ok ? 'copied' : 'copy-failed');
      setTimeout(() => {
        button.textContent = original;
        button.classList.remove('copied', 'copy-failed');
      }, 1600);
    }
  }

  // ---------- wiring ----------------------------------------------------------------
  function wireControls() {
    $('btn-export').addEventListener('click', exportCsv);
    $('btn-snapshot-cmd').addEventListener('click', (ev) => {
      copyText('/analytics snapshot', ev.currentTarget);
    });
    $('alert-search').addEventListener('input', (ev) => {
      state.alertQuery = ev.target.value;
      if (state.lastData) renderAlerts(state.lastData);
    });
    $('item-search').addEventListener('input', (ev) => {
      state.itemQuery = ev.target.value;
      if (state.lastData) renderItems(state.lastData);
    });
    document.querySelectorAll('#severity-seg .seg-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('#severity-seg .seg-btn').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        state.severity = btn.dataset.sev;
        if (state.lastData) renderAlerts(state.lastData);
      });
    });
    document.querySelectorAll('#range-seg .seg-btn').forEach((btn) => {
      btn.classList.toggle('active', state.range === Number(btn.dataset.range));
      btn.addEventListener('click', () => {
        document.querySelectorAll('#range-seg .seg-btn').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        state.range = Number(btn.dataset.range);
        try { localStorage.setItem('solidus-chart-range', String(state.range)); } catch (e) { /* fine */ }
        if (state.lastData) {
          const hist = rows();
          renderVolumeChart(hist.slice(0, state.range));
          renderHistory(hist.slice(0, state.range));
        }
      });
    });
    $('btn-fullscreen').addEventListener('click', () => {
      const modal = $('chart-modal');
      const host = $('modal-chart');
      host.replaceChildren();
      const hist = rows();
      if (hist.length) host.append(buildChartSvg(hist.slice(0, state.range), { W: 1040, H: 460 }));
      else host.textContent = 'No chart data yet';
      if (typeof modal.showModal === 'function') modal.showModal();
      else modal.setAttribute('open', '');
    });
    $('modal-close').addEventListener('click', () => {
      const modal = $('chart-modal');
      if (typeof modal.close === 'function') modal.close(); else modal.removeAttribute('open');
    });
    $('chart-modal').addEventListener('click', (ev) => {
      // backdrop click closes (clicks inside .modal-box are ignored)
      if (ev.target === ev.currentTarget) {
        const modal = ev.currentTarget;
        if (typeof modal.close === 'function') modal.close(); else modal.removeAttribute('open');
      }
    });
    $('status').addEventListener('click', () => load());
  }

  // ---------- polling with exponential backoff --------------------------------------
  const BASE_DELAY = 30000;
  const BACKOFF_STEPS = [30000, 60000, 120000, 300000];
  let failures = 0;
  let timer = null;
  const schedule = (ms) => {
    clearTimeout(timer);
    timer = setTimeout(load, ms);
  };
  const setStatus = (cls) => { $('status').className = `status ${cls}`; };

  async function load() {
    clearTimeout(timer);
    try {
      const response = await fetch('/api/data', { headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      render(await response.json());
      failures = 0;
      $('status-text').textContent = `Updated ${new Date().toLocaleTimeString()}`;
      setStatus('good');
      schedule(BASE_DELAY);
    } catch (error) {
      ++failures;
      const next = BACKOFF_STEPS[Math.min(failures - 1, BACKOFF_STEPS.length - 1)];
      $('status-text').textContent = `Offline — retrying in ${Math.round(next / 1000)}s`;
      setStatus('bad');
      console.warn('Analytics dashboard request failed', error);
      schedule(next);
    }
  }

  wireControls();
  load();
})();
