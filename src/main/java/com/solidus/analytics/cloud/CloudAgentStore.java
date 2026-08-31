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

    public void logCommand(long ts, String rid, String cmd, String target, String actorUid,
                           String actorName, String actorRole, String status, String code,
                           String resultJson, String idemKey) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "INSERT INTO cloud_command_log(ts, rid, cmd, target, actor_uid, actor_name, actor_role, status, code, result_json, idem_key)\n"
                + "        VALUES(?,?,?,?,?,?,?,?,?,?,?)");) {
            ps.setLong(1, ts);
            ps.setString(2, rid);
            ps.setString(3, cmd);
            ps.setString(4, target);
            ps.setString(5, actorUid);
            ps.setString(6, actorName);
            ps.setString(7, actorRole);
            ps.setString(8, status);
            ps.setString(9, code);
            ps.setString(10, resultJson);
            ps.setString(11, idemKey);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Failed to write command audit row", (Throwable)e);
        }
    }

    // ---- idempotency window ------------------------------------------

    /** Returns the stored result JSON for an already-executed idemKey, or null. */
    public String findIdempotent(String idemKey) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "SELECT result_json FROM cloud_idempotency WHERE idem_key = ?");) {
            ps.setString(1, idemKey);
            try (ResultSet rs = ps.executeQuery();) {
                return rs.next() ? rs.getString("result_json") : null;
            }
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.error("[Cloud] Idempotency lookup failed for {}", (Object)idemKey, (Object)e);
            return null;
        }
    }

    public void putIdempotent(String idemKey, String cmd, String status, String resultJson) {
        try (PreparedStatement ps = this.connection.prepareStatement(
                "INSERT OR REPLACE INTO cloud_idempotency(idem_key, cmd, ts, status, result_json)\n"
                + "        VALUES(?, ?, ?, ?, ?");) {
            ps.setString(1, idemKey);
            ps.setString(2, cmd);
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
    public void prune(long idemWindowMs, int auditRetentionDays) {
        try (Statement st = this.connection.createStatement();) {
            st.executeUpdate("DELETE FROM cloud_idempotency WHERE ts < " + (System.currentTimeMillis() - idemWindowMs));
            st.executeUpdate("DELETE FROM cloud_command_log WHERE ts < "
                + (System.currentTimeMillis() - (long)auditRetentionDays * 86400000L));
        }
        catch (SQLException e) {
            SolidusAnalyticsMod.LOGGER.debug("[Cloud] prune sweep failed", (Throwable)e);
        }
    }
}
