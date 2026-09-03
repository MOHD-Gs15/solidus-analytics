package com.solidus.analytics.dashboard;

import com.solidus.analytics.storage.DirectDb;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the read-only wealth-distribution provider that feeds the
 * dashboard donut and richest-players table.
 *
 * <p>Fixture convention mirrors production: economy.db stores DECIMAL S$
 * units; the provider must return cents. 20 players with balances 20..1 S$
 * give exact, hand-checkable shares (total 210 S$).</p>
 */
class WealthDistributionProviderTest {

    private static final int PLAYERS = 20;
    private static final long TOTAL_CENTS = 21_000L; // sum(1..20) S$

    @TempDir
    Path tempDir;

    private Path createEconomyFixture() throws SQLException {
        Path dbPath = this.tempDir.resolve("economy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, player_name TEXT, balance REAL)");
            try (Statement ins = conn.createStatement()) {
                for (int i = 0; i < WealthDistributionProviderTest.PLAYERS; ++i) {
                    // player i has (20 - i) S$; P0 richest, DESC order matches names
                    ins.executeUpdate(String.format(
                        "INSERT INTO player_balances (uuid, player_name, balance) VALUES ('u%d', 'P%d', %.1f)",
                        i, i, (double)(WealthDistributionProviderTest.PLAYERS - i)));
                }
            }
        }
        return dbPath;
    }

    @Test
    void computesSharesRanksAndCentsFromDescBalances() throws SQLException {
        WealthDistributionProvider provider =
            new WealthDistributionProvider(this.createEconomyFixture().toAbsolutePath().toString(), 0L);
        WealthDistributionProvider.WealthDistribution data = provider.compute(1_234L);

        assertNotNull(data);
        assertEquals(1_234L, data.computedAt());
        assertEquals(WealthDistributionProviderTest.TOTAL_CENTS, data.totalWealthCents());
        assertEquals(WealthDistributionProviderTest.PLAYERS, data.playerCount());
        // top1% of 20 players = ceil(0.2) = 1 player = 20 S$ / 210 S$
        assertEquals(20.0 / 210.0, data.top1Share(), 1e-9);
        // top10% of 20 players = ceil(2.0) = 2 players = (20 + 19) S$ / 210 S$
        assertEquals(39.0 / 210.0, data.top10Share(), 1e-9);

        List<WealthDistributionProvider.TopPlayer> players = data.topPlayers();
        assertEquals(WealthDistributionProvider.TOP_PLAYERS_LIMIT, players.size());
        for (int i = 0; i < players.size(); ++i) {
            assertEquals(i + 1, players.get(i).rank());
            assertEquals("P" + i, players.get(i).name());
            assertEquals((long)(WealthDistributionProviderTest.PLAYERS - i) * 100L, players.get(i).balanceCents());
            assertEquals((double)(WealthDistributionProviderTest.PLAYERS - i) / 210.0, players.get(i).share(), 1e-9);
        }
    }

    @Test
    void emptyEconomyYieldsNullNotZeros() throws SQLException {
        Path dbPath = this.tempDir.resolve("economy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, player_name TEXT, balance REAL)");
        }
        WealthDistributionProvider provider = new WealthDistributionProvider(dbPath.toAbsolutePath().toString(), 0L);
        assertNull(provider.compute(1L), "no players yet -> null, matching the snapshot convention");
        assertNull(provider.get(), "get() surfaces the null without throwing");
    }

    @Test
    void getFailsOpenOnMissingDatabase() {
        WealthDistributionProvider provider =
            new WealthDistributionProvider(this.tempDir.resolve("does-not-exist.db").toAbsolutePath().toString(), 0L);
        // a fresh sqlite file has no player_balances table: the scan must
        // degrade to null, never throw into the payload builder
        assertNull(provider.get());
    }

    @Test
    void getServesCacheWithinTtlAndRefreshesAfterExpiry() throws SQLException {
        Path dbPath = this.createEconomyFixture();
        WealthDistributionProvider caching = new WealthDistributionProvider(dbPath.toAbsolutePath().toString(), 60_000L);
        WealthDistributionProvider.WealthDistribution first = caching.get();
        assertNotNull(first);
        assertEquals(WealthDistributionProviderTest.TOTAL_CENTS, first.totalWealthCents());

        // grow the economy while the cache should still be warm
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO player_balances (uuid, player_name, balance) VALUES ('u999', 'P999', 1000.0)");
        }
        assertEquals(WealthDistributionProviderTest.TOTAL_CENTS, caching.get().totalWealthCents(),
            "within TTL the cached value is served, no extra scan");

        WealthDistributionProvider eager = new WealthDistributionProvider(dbPath.toAbsolutePath().toString(), 0L);
        assertEquals(WealthDistributionProviderTest.TOTAL_CENTS + 100_000L, eager.get().totalWealthCents(),
            "zero TTL recomputes and sees the new player");
    }

    @Test
    void providerConnectionsAreQueryOnly() throws SQLException {
        // the convention the provider relies on: any connection opened through
        // DirectDb physically cannot write, so a future SQL mistake in the
        // provider can never mutate Core's economy
        Path dbPath = this.createEconomyFixture();
        try (Connection conn = DirectDb.openReadOnly(dbPath.toAbsolutePath().toString());
             Statement st = conn.createStatement()) {
            SQLException thrown = assertThrows(SQLException.class, () ->
                st.executeUpdate("INSERT INTO player_balances (uuid, player_name, balance) VALUES ('x', 'x', 1.0)"));
            assertNotNull(thrown);
        }
        // and the row really is not there
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM player_balances")) {
            assertTrue(rs.next());
            assertEquals(WealthDistributionProviderTest.PLAYERS, rs.getInt(1));
        }
    }

    @Test
    void blankAndNullNamesFallBackToUnknown() throws SQLException {
        Path dbPath = this.tempDir.resolve("economy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE player_balances (uuid TEXT PRIMARY KEY, player_name TEXT, balance REAL)");
            st.executeUpdate("INSERT INTO player_balances VALUES ('a', NULL, 50.0)");
            st.executeUpdate("INSERT INTO player_balances VALUES ('b', '   ', 10.0)");
        }
        WealthDistributionProvider provider = new WealthDistributionProvider(dbPath.toAbsolutePath().toString(), 0L);
        List<WealthDistributionProvider.TopPlayer> players = provider.compute(1L).topPlayers();
        assertEquals("unknown", players.get(0).name());
        assertEquals("unknown", players.get(1).name());
    }
}
