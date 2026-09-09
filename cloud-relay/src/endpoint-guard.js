'use strict';
// Solidus Cloud Relay - push endpoint SSRF guard (SA2-005, CWE-918).
//
// The relay POSTs alert payloads to client-supplied Web-Push endpoints from
// its own network position. The old subscribe-time filter blocked IP
// LITERALS only, so https://localhost:9200/, https://metadata.google.internal/
// and https://kubernetes.default.svc/ all passed, the port was unchecked,
// and nothing re-validated at delivery time.
//
// This guard is used in BOTH places: when a subscription is stored
// (server.js /api/push-subscribe) and immediately before every delivery
// (alerts.js) - so a hostname that later rebinds, or an endpoint edited by
// another path, is caught too.

const BANNED_SUFFIXES = [
  '.localhost', '.local', '.internal', '.svc', '.lan', '.corp', '.home',
  '.localdomain', '.invalid', '.example', '.test',
];

function isPrivateIpv4(host) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  if ([a, b, Number(m[3]), Number(m[4])].some((n) => n > 255)) return true; // malformed -> reject
  if (a === 10 || a === 127 || a === 0) return true;                       // private / loopback / this-network
  if (a === 169 && b === 254) return true;                                  // link-local (cloud metadata)
  if (a === 172 && b >= 16 && b <= 31) return true;                         // RFC1918
  if (a === 192 && b === 168) return true;                                  // RFC1918
  if (a === 100 && b >= 64 && b <= 127) return true;                        // CGNAT
  if (a >= 224) return true;                                                // multicast / reserved
  return false;
}

/**
 * True when the endpoint URL is a public, https, port-443 push-service URL
 * that is safe for the relay to POST alert payloads to.
 * @param {string} raw the endpoint string exactly as the client supplied it
 */
function isSafePushEndpoint(raw) {
  if (typeof raw !== 'string' || !raw || raw.length > 512) return false;
  let ep;
  try { ep = new URL(raw); } catch { return false; }
  if (ep.protocol !== 'https:') return false;
  if (ep.username || ep.password) return false;
  const host = (ep.hostname || '').toLowerCase();
  if (!host) return false;
  // Port: web-push services live on 443. Anything else is a pivot attempt
  // against an internal service that happens to speak TLS.
  if (ep.port && ep.port !== '443') return false;
  // IP literals (v4 dotted, v6 with colons, and the WHATWG-normalized forms
  // of numeric/hex shorthands which all normalize into one of those shapes).
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(':')) return false;
  if (isPrivateIpv4(host)) return false;
  // localhost and banned internal suffixes.
  if (host === 'localhost' || BANNED_SUFFIXES.some((sfx) => host.endsWith(sfx))) return false;
  // Single-label hostname (no dot): no real push service uses one, and this
  // is exactly the shape internal DNS search domains resolve through.
  if (!host.includes('.')) return false;
  // Known cloud-metadata hosts even though the suffix list already catches
  // .internal - explicit is better than implicit here.
  if (host === 'metadata.google.internal' || host === 'metadata.goog') return false;
  return true;
}

module.exports = { isSafePushEndpoint };
