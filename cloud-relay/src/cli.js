'use strict';
// Solidus Cloud Relay - admin CLI.
//   npm run user    -- --name <owner> --password <pass> [--role owner]
//   npm run pair    -- --user <owner> --serverId <id> --secret <64hex> [--name "My Server"]
//   npm run entitle -- --serverId <id> --status active|expired [--renewsAt <epochms>]
//   npm run keys    (generates VAPID keys when web-push is installed)

const crypto = require('node:crypto');
const { Store } = require('./store');

const cmd = process.argv[2];
const args = {};
for (let i = 3; i < process.argv.length; i += 2) {
  const key = String(process.argv[i]).replace(/^--/, '');
  args[key] = process.argv[i + 1];
}
const store = new Store();

switch (cmd) {
  case 'user': {
    if (!args.name || !args.password) fail('usage: npm run user -- --name <you> --password <pass> [--role owner]');
    if (store.findUser(args.name)) fail(`user "${args.name}" already exists`);
    // audit C-4: an arbitrary --role string (e.g. "Admin") used to be stored
    // verbatim; the relay's RANK lookup then failed open and the account
    // passed every gate. Roles are a closed set.
    const ROLES = ['viewer', 'mod', 'admin', 'owner'];
    const role = args.role || (store.users.users.length === 0 ? 'owner' : 'viewer');
    if (!ROLES.includes(role)) fail(`invalid --role "${role}" (allowed: ${ROLES.join(', ')})`);
    const { salt, hash } = store.hashPassword(args.password);
    store.users.users.push({
      id: 'u-' + crypto.randomUUID().slice(0, 8),
      name: args.name, salt, hash,
      role,
      created: Date.now(),
    });
    store.saveUsers();
    console.log(`user "${args.name}" created${store.users.users.length === 1 ? ' as OWNER (first user)' : ''}`);
    break;
  }
  case 'pair': {
    const user = store.findUser(args.user || '');
    if (!user) fail('unknown --user (create it first with npm run user)');
    if (!args.serverId || !args.secret) fail('usage: npm run pair -- --user <owner> --serverId <id> --secret <64hex> [--name "My Server"]');
    const rec = store.pairServer({
      serverId: args.serverId, secret: args.secret, name: args.name, userId: user.id,
    });
    console.log(`server ${rec.serverId} (${rec.name}) paired to ${user.name}; relay stored sha256(secret) only`);
    break;
  }
  case 'entitle': {
    const rec = store.findServer(args.serverId || '');
    if (!rec) fail('unknown serverId');
    rec.subscription = rec.subscription || {};
    rec.subscription.status = args.status || 'active';
    if (args.renewsAt) rec.subscription.renewsAt = Number(args.renewsAt);
    store.saveServers();
    console.log(`server ${rec.serverId} subscription -> ${JSON.stringify(rec.subscription)}`);
    break;
  }
  case 'keys': {
    try {
      const webpush = require('web-push');
      const keys = webpush.generateVAPIDKeys();
      console.log('VAPID_PUBLIC_KEY=' + keys.publicKey);
      console.log('VAPID_PRIVATE_KEY=' + keys.privateKey);
      console.log('# export these two variables before starting the relay');
    } catch {
      fail('web-push is not installed (npm install)');
    }
    break;
  }
  default:
    console.log('commands: user | pair | entitle | keys');
}

function fail(msg) { console.error(msg); process.exit(1); }
