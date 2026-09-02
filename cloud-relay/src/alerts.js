'use strict';
// Solidus Cloud Relay - alert rules engine + notification delivery
// (PROTOCOL.md §13). Web Push via optional `web-push`; Discord DM is a stub.

const crypto = require('node:crypto');
const { config } = require('./config');

let webpush = null;
try { webpush = require('web-push'); } catch { /* optional */ }

function initPush() {
  if (!webpush) return false;
  if (config.vapid.publicKey && config.vapid.privateKey) {
    webpush.setVAPIDDetails(config.vapid.subject, config.vapid.publicKey, config.vapid.privateKey);
    return true;
  }
  return false;
}

const pushReady = initPush();

// metric extraction from event payloads
function metricValue(metric, d) {
  if (!d) return null;
  switch (metric) {
    case 'tps': return d.tps1 ?? null;
    case 'ram': return d.heapMaxB > 0 ? (d.heapUsedB / d.heapMaxB) * 100 : null;
    case 'cpu': return d.sysPct ?? null;
    default: return null;
  }
}

class AlertEngine {
  constructor(store) {
    this.store = store;
    this.state = new Map(); // ruleId -> {firstBreachedAt, firedAt, silenceUntil}
    if (!this.store.alerts.rules.find((r) => r.id === 'builtin-heartbeat')) {
      this.store.alerts.rules.push({
        id: 'builtin-heartbeat', metric: 'heartbeat', op: 'absent', threshold: 0,
        forMs: config.heartbeatTimeoutMs, channels: ['push'], silenceMin: 30, enabled: true, builtin: true,
      });
    }
  }

  /** Evaluates a live event for one server. */
  onEvent(serverId, type, d) {
    for (const rule of this.store.alerts.rules) {
      if (!rule.enabled || rule.serverId !== serverId) continue;
      const want = { 'health.tps': 'tps', 'health.ram': 'ram', 'health.cpu': 'cpu' }[type];
      if (!want || rule.metric !== want) continue;
      const v = metricValue(rule.metric, d);
      if (v == null) continue;
      const breached = rule.op === '<' ? v < rule.threshold : v > rule.threshold;
      const st = this.stateOf(rule.id);
      if (breached) {
        st.firstBreachedAt ??= Date.now();
        if (Date.now() - st.firstBreachedAt >= (rule.forMs || 0)) this.fire(serverId, rule, st, `${type} ${rule.op} ${rule.threshold} (now ${Math.round(v * 10) / 10})`);
      } else {
        st.firstBreachedAt = null;
      }
    }
  }

  /** Absence rule: agent heartbeat lost. */
  onHeartbeatLost(serverId) {
    const rule = this.store.alerts.rules.find((r) => r.id === 'builtin-heartbeat');
    if (rule) this.fire(serverId, rule, this.stateOf('builtin-heartbeat'), 'agent heartbeat lost > 120s');
  }

  /** Fires on security-change events (G5 compensating control). */
  onSecurityChange(serverId, detail) {
    this.fire(serverId,
      { id: 'security-' + crypto.randomUUID().slice(0, 6), channels: ['push'], silenceMin: 0 },
      this.stateOf('security'), 'agent.security.change: ' + detail);
  }

  stateOf(id) {
    if (!this.state.has(id)) this.state.set(id, {});
    return this.state.get(id);
  }

  fire(serverId, rule, st, reason) {
    const now = Date.now();
    // audit C-11: the global maintenance window set by alert.silence was
    // written to the store but never read - the command was a no-op that the
    // UI happily reported as applied (§13: suppression, not deletion).
    const silencedUntil = this.store.alerts.silenceUntil || 0;
    if (silencedUntil > now) return;
    if (st.firedAt && now - st.firedAt < (rule.silenceMin || 0) * 60000) return;
    st.firedAt = now;
    const payload = { serverId, code: rule.metric === 'heartbeat' ? 'agent.heartbeat.lost' : 'alert.' + rule.metric, reason, ts: now };
    this.store.audit({ kind: 'alert', ...payload });
    if (!pushReady || !(rule.channels || []).includes('push')) {
      console.log(`[alert] ${serverId}: ${reason}`);
      return;
    }
    const rec = this.store.findServer(serverId);
    for (const sub of (rec && rec.pushSubs) || []) {
      webpush.sendNotification(sub, JSON.stringify(payload)).catch((err) => {
        if (err && (err.statusCode === 404 || err.statusCode === 410)) {
          rec.pushSubs = rec.pushSubs.filter((x) => x !== sub);
          this.store.saveServers();
        }
      });
    }
  }
}

module.exports = { AlertEngine, pushReady };
