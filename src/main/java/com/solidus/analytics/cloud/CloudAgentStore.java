package com.solidus.analytics.cloud;

import com.solidus.analytics.SolidusAnalyticsMod;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * CloudAgentStore - the agent's own SQLite database (cloud.db).
 *
 * <p>Deliberately separate from analytics.db (Analytics owns that file) and
 * from Core's databases (query_only rule). Three tables:</p>
 * <ul>
 *   <li>{@code cloud_state} - durable veto state (pause flags, frozen
 *       accounts) re-applied at boot BEFORE the hook can allow anything
 *       (PROTOCOL.md &sect;5.4: a server that restarts frozen comes back frozen).</li>
 *   <li>{@code cloud_command_log} - local mirror of every command outcome so
 *       the server owner keeps a tamper-resistant copy even if the relay is
 *       wiped (PROTOCOL.md &sect;12).</li>
 *   <li>{@code cloud_idempotency} - executed idemKeys with their results,
 *       48h window, survives restarts (PROTOCOL.md &sect;8 / G3: a retried
 *       network submission can never double-grant).</li>
 * </ul>
 *
 * <p>All methods are synchronous and fast; callers run them on the agent's
 * executor. A 5s busy timeout keeps us from ever wedging on SQLite locks.</p>
 */
public final class CloudAgentStore {
    private final Path dbPath;
    private Connection connection;
    /** Generic cap for wire-supplied persisted fields (audit B-11). */
    static final int MAX_FIELD = 512;

    public CloudAgentStore(Path configDir) {
        this.dbPath = configDir.resolve("cloud.db");
    }

    public void initialize() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath.toAbsolutePath());
        try (Statement st = this.connection.createStatement();) {
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("    CREATE TABLE IF NOT EXISTS cloud_state (\n"
                + "        key TEXT PRIMARY KEY,\n        value TEXT NOT NULL\n    )");
            st.execute("    CREATE TABLE IF NOT EXISTS cloud_command_log (\n"
                + "        id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                + "        ts INTEGER NOT NULL,\n"
                + "        rid TEXT,\n"
                + "        cmd TEXT NOT NULL,\n"
                + "        target TEXT,\n"
                + "        actor_uid TEXT,\n"
                + "        actor_name TEXT,\n"
                + "        actor_role TEXT,\n"
                + "        status TEXT NOT NULL,\n"
                + "        code TEXT,\n"
                + "        result_json TEXT,\n"
                + "        idem_key TEXT\n    )");
            st.execute("    CREATE TABLE IF NOT EXISTS cloud_idempotency (\n"
                + "        idem_key TEXT PRIMARY KEY,\n"
                + "        cmd TEXT NOT NULL,\n"
                + "        ts INTEGER NOT NULL,\n"
                + "        status TEXT NOT NULL,\n"
                + "        result_json TEXT\n    )");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cclog_ts ON cloud_command_log(ts)");
        }
        SolidusAnalyticsMod.LOGGER.info("[Cloud] Agent store ready at {}", (Object)this.dbPath.toAbsolutePath());
    }

    public void shutdown() {
        try {
            if (this.connection != null) {
                this.connection.close();
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] Error closing cloud.db", (Throwable)e);
        }
    }

    // ---- state persistence (veto flags survive restarts) -------------

    public void saveState(String key, String json) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "INSERT INTO cloud_state(key, value) VALUES(?, ?)\n"
                + "        ON CONFLICT(key) DO UPDATE SET value = excluded.value");) {
            ps.setString(1, key);
            ps.setString(2, json);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to persist cloud state '{}'", (Object)key, (Object)e);
        }
    }

    public String loadState(String key) {
        try (PreparedStatement ps = this.connection.prepareStatement("SELECT value FROM cloud_state WHERE key = ?");) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery();) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.warn("[Cloud] Failed to load cloud state '{}'", (Object)key, (Object)e);
        }
        return null;
    }

    // ---- command audit mirror ---------------------------------------

    public synchronized void logCommand(long ts, String rid, String cmd, String target, String actorUid,
                           String actorName, String actorRole, String status, String code,
                           String resultJson, String idemKey) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "INSERT INTO cloud_command_log(ts, rid, cmd, target, actor_uid, actor_name, actor_role, status, code, result_json, idem_key)\n"
                + "        VALUES(?,?,?,?,?,?,?,?,?,?,?)");) {
            ps.setLong(1, ts);
            ps.setString(2, truncate(rid, MAX_RID_LEN));
            ps.setString(3, truncate(cmd, MAX_RID_LEN));
            ps.setString(4, truncate(target, MAX_FIELD));
            ps.setString(5, truncate(actorUid, MAX_FIELD));
            ps.setString(6, truncate(actorName, MAX_FIELD));
            ps.setString(7, truncate(actorRole, MAX_FIELD));
            ps.setString(8, truncate(status, MAX_FIELD));
            ps.setString(9, truncate(code, MAX_FIELD));
            ps.setString(10, resultJson);
            ps.setString(11, truncate(idemKey, MAX_IDEM_KEY_LEN));
            ps.executeUpdate();
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to write command audit row", (Throwable)e);
        }
    }

    // ---- idempotency window ------------------------------------------

    /** A pending claim older than this is assumed crashed and becomes reclaimable. */
    static final long PENDING_STALE_MS = 10 * 60_000L;
    /** Length caps for wire-supplied fields persisted to cloud.db (audit B-11). */
    static final int MAX_IDEM_KEY_LEN = 128;
    static final int MAX_RID_LEN = 64;

    /** Outcome of an atomic idempotency claim (audit B-2 / G3). */
    public record Claim(boolean claimed, String existingStatus, String existingResultJson, long existingTs) {
        /** A store failure - the router must fail CLOSED (no execution). */
        public boolean storeError() {
            return "error".equals(this.existingStatus);
        }
    }

    /** Returns the stored result JSON for an already-executed idemKey, or null. */
    public synchronized String findIdempotent(String idemKey) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT result_json FROM cloud_idempotency WHERE idem_key = ?");) {
            ps.setString(1, truncate(idemKey, MAX_IDEM_KEY_LEN));
            try (ResultSet rs = ps.executeQuery();) {
                return rs.next() ? rs.getString("result_json") : null;
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Idempotency lookup failed for {}", (Object)idemKey, (Object)e);
            return null;
        }
    }

    /**
     * Atomic claim for G3 idempotency (audit B-2). {@code INSERT OR IGNORE} is
     * the linearization point: exactly one caller ever claims a fresh idemKey,
     * concurrent or retried submissions with the same key observe the existing
     * row instead of racing past a check-then-act gap. A pending claim left by a
     * crashed process becomes reclaimable after {@link #PENDING_STALE_MS}.
     *
     * <p>Store failures fail CLOSED: the caller sees {@code storeError()} and
     * must reject the command rather than execute without idempotency.</p>
     */
    public synchronized Claim claimIdempotent(String idemKey, String cmd) {
        String key = truncate(idemKey, MAX_IDEM_KEY_LEN);
        long now = System.currentTimeMillis();
        try (PreparedStatement ins = this.connection.prepareStatement(
                "INSERT OR IGNORE INTO cloud_idempotency(idem_key, cmd, ts, status) VALUES(?, ?, ?, 'pending')")) {
            ins.setString(1, key);
            ins.setString(2, truncate(cmd, MAX_RID_LEN));
            ins.setLong(3, now);
            if (ins.executeUpdate() == 1) {
                return new Claim(true, "pending", null, now);
            }
            Claim existing = this.readClaim(key);
            if (existing != null && "pending".equals(existing.existingStatus())
                && now - existing.existingTs() > PENDING_STALE_MS) {
                try (PreparedStatement del = this.connection.prepareStatement(
                        "DELETE FROM cloud_idempotency WHERE idem_key = ? AND status = 'pending'")) {
                    del.setString(1, key);
                    del.executeUpdate();
                }
                ins.clearParameters();
                ins.setString(1, key);
                ins.setString(2, truncate(cmd, MAX_RID_LEN));
                ins.setLong(3, now);
                if (ins.executeUpdate() == 1) {
                    return new Claim(true, "pending", null, now);
                }
            }
            return existing != null ? existing : new Claim(false, "pending", null, now);
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Idempotency claim failed for {}", (Object)idemKey, (Object)e);
            return new Claim(false, "error", null, 0L);
        }
    }

    private Claim readClaim(String key) throws SQLException {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT status, result_json, ts FROM cloud_idempotency WHERE idem_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Claim(false, rs.getString("status"), rs.getString("result_json"), rs.getLong("ts"));
                }
            }
        }
        return null;
    }

    /** Replaces the legacy put: updates the row claimed by THIS execution. */
    public synchronized void finalizeIdempotent(String idemKey, String cmd, String status, String resultJson) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "UPDATE cloud_idempotency SET cmd = ?, ts = ?, status = ?, result_json = ? WHERE idem_key = ?")) {
            ps.setString(1, truncate(cmd, MAX_RID_LEN));
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, status);
            ps.setString(4, resultJson);
            ps.setString(5, truncate(idemKey, MAX_IDEM_KEY_LEN));
            ps.executeUpdate();
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to finalize idempotency key", (Throwable)e);
        }
    }

    /** Kept for compatibility: stores a terminal result directly. */
    public synchronized void putIdempotent(String idemKey, String cmd, String status, String resultJson) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "INSERT OR REPLACE INTO cloud_idempotency(idem_key, cmd, ts, status, result_json)\n"
                + "        VALUES(?, ?, ?, ?, ?");) {
            ps.setString(1, truncate(idemKey, MAX_IDEM_KEY_LEN));
            ps.setString(2, truncate(cmd, MAX_RID_LEN));
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, status);
            ps.setString(5, resultJson);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to record idempotency key", (Throwable)e);
        }
    }

    /** Prunes idempotency entries older than the 48h window and audit rows past retention. */
    public synchronized void prune(long idemWindowMs, int auditRetentionDays) {
        try (Statement st = this.connection.createStatement();) {
            st.executeUpdate("DELETE FROM cloud_idempotency WHERE ts < " + (System.currentTimeMillis() - idemWindowMs));
            st.executeUpdate("DELETE FROM cloud_command_log WHERE ts < "
                + (System.currentTimeMillis() - (long)auditRetentionDays * 86400000L));
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] prune sweep failed", (Throwable)e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }
}
