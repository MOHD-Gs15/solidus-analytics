package com.solidus.analytics.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DirectDb - short-lived read-only connections to another mod's SQLite
 * database (Solidus Core's economy.db / auctions.db).
 *
 * <p>Analytics reads Core databases directly as a fallback and for aggregate
 * scans. Every connection opened here shares two properties:</p>
 * <ul>
 *   <li><b>busy_timeout (250 ms)</b>: SQLite readers can still collide with
 *       Core's writer while a WAL checkpoint runs; without a busy timeout
 *       such a collision throws SQLITE_BUSY instantly and the check silently
 *       degrades. A short bounded wait turns transient collisions into
 *       successes without ever blocking a server thread for long.</li>
 *   <li><b>query_only</b>: the connection physically cannot write - a
 *       read-only guarantee at the database level, not just by convention.</li>
 * </ul>
 *
 * <p>Connections are intentionally short-lived (one check, one scan): Core
 * owns these files, so Analytics must never hold a persistent handle that
 * could interfere with Core's own checkpointing.</p>
 */
public final class DirectDb {

    private DirectDb() {
    }

    /**
     * Opens a read-only connection with a 250 ms busy timeout.
     *
     * @param dbPath absolute path to the SQLite database file
     * @return an open connection (caller closes it)
     * @throws SQLException if the file cannot be opened or the pragmas fail
     */
    public static Connection openReadOnly(String dbPath) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout=250");
            st.execute("PRAGMA query_only=ON");
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // closing a just-failed connection must not mask the original error
            }
            throw e;
        }
        return conn;
    }
}
