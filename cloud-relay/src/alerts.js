'use strict';
// Solidus Cloud Relay - alert rules engine + notification delivery
// (PROTOCOL.md §13). Web Push via optional `web-push`; Discord DM is a stub.

const crypto = require('node:crypto');
const { config } = require('./config');
const { isSafePushEndpoint } = require('./endpoint-guard');

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
    // audit C-11: the maintenance window set by alert.silence. SECURITY
    // (SA2-006): silence is PER-SERVER now (store.alerts.silence[serverId]);
    // the legacy global silenceUntil is still honored for rows written by
    // relay versions before the fix.
    const silencedUntil = Math.max(
      (this.store.alerts.silence && this.store.alerts.silence[serverId]) || 0,
      this.store.alerts.silenceUntil || 0);
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
      // SECURITY (SA2-005): re-validate at DELIVERY time. The subscribe-time
      // guard can be defeated later by DNS rebinding (a public hostname that
      // starts resolving to 169.254.169.254) or by any path that touches the
      // stored record. A failed check drops the subscription and audits it.
      if (!isSafePushEndpoint(sub.endpoint)) {
        console.error(`[alert] ${serverId}: dropping unsafe push endpoint (SSRF guard)`);
        this.store.audit({ kind: 'alert', serverId, status: 'dropped', code: 'E_ENDPOINT_UNSAFE' });
        rec.pushSubs = rec.pushSubs.filter((x) => x !== sub);
        this.store.saveServers();
        continue;
      }
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
