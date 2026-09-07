package com.solidus.analytics.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.solidus.analytics.storage.CoreLedgerAccess;

/**
 * 2.1.4: FraudDetector rides the same CoreLedgerAccess bridge as the engine
 * collectors. These tests pin the two behaviors the migration must preserve:
 *
 * <ol>
 *   <li><b>Parity</b> - bridge mode (CoreLedgerAccess over a connection
 *       provider) produces the SAME alerts as the direct SQLite file path on
 *       identical data. In production the provider is Core's live
 *       TransactionLog connection; here it is a plain SQLite file, which is
 *       exactly what the provider seam is for.</li>
 *   <li><b>Fail-open</b> - a broken ledger source (provider throws) degrades
 *       to an empty scan with no exception escaping runAllChecks(). The
 *       detector is a premium feature and must never take a server down.</li>
 * </ol>
 *
 * <p>Dialect portability of the fraud SQL against REAL MariaDB is proven by
 * the CI-gated {@code MySqlPremiumCloudBridgeTest}.</p>
 */
class FraudDetectorBridgeTest {

    private static final String DDL = """
        CREATE TABLE transaction_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            type TEXT NOT NULL,
            player_uuid TEXT NOT NULL,
            player_name TEXT NOT NULL,
            target_uuid TEXT,
            target_name TEXT,
            amount REAL NOT NULL,
            item_material TEXT,
            item_quantity INTEGER,
            description TEXT
        )
        """;

    private static Path seedLedger(String... inserts) throws Exception {
        Path economyDb = Files.createTempDirectory("solidus-fraud-bridge").resolve("economy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + economyDb);
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL);
            for (String insert : inserts) {
                stmt.execute(insert);
            }
        }
        return economyDb;
    }

    private static String shopRow(long ts, String uuid, String name, double amount) {
        return "INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, amount) VALUES ("
            + ts + ", 'SHOP_SELL', '" + uuid + "', '" + name + "', " + amount + ")";
    }

    private static String payRow(long ts, String fromUuid, String fromName, String toUuid, String toName, double amount) {
        return "INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, target_uuid, target_name, amount) VALUES ("
            + ts + ", 'PAY_SEND', '" + fromUuid + "', '" + fromName + "', '" + toUuid + "', '" + toName + "', " + amount + ")";
    }

    private static CoreLedgerAccess bridgeOver(Path economyDb) {
        return CoreLedgerAccess.forConnectionProvider(work -> {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + economyDb)) {
                return work.run(conn);
            }
        });
    }

    @Test
    void bridgeModeProducesTheSameAlertsAsTheDirectFilePath() throws Exception {
        long now = System.currentTimeMillis();
        // Alice earns 300.25 S$ (whale); seven small earners at 0.50 keep the
        // server average low; Alice<->Bob ping-pong is a circular 2-loop.
        Path economyDb = seedLedger(
            shopRow(now, "alice", "Alice", 100.10),
            shopRow(now, "alice", "Alice", 100.10),
            shopRow(now, "alice", "Alice", 100.05),
            shopRow(now, "p1", "P1", 0.50),
            shopRow(now, "p2", "P2", 0.50),
            shopRow(now, "p3", "P3", 0.50),
            shopRow(now, "p4", "P4", 0.50),
            shopRow(now, "p5", "P5", 0.50),
            shopRow(now, "p6", "P6", 0.50),
            shopRow(now, "bob", "Bob", 0.50),
            payRow(now, "alice", "Alice", "bob", "Bob", 0.50),
            payRow(now, "bob", "Bob", "alice", "Alice", 0.50));

        FraudDetector direct = new FraudDetector(null, economyDb.toString());
        direct.runAllChecks();
        List<FraudDetector.FraudAlert> directAlerts = direct.getRecentAlerts(100);

        FraudDetector bridged = new FraudDetector(null, economyDb.toString());
        bridged.setLedgerAccess(bridgeOver(economyDb));
        bridged.runAllChecks();
        List<FraudDetector.FraudAlert> bridgeAlerts = bridged.getRecentAlerts(100);

        assertEquals(directAlerts.size(), bridgeAlerts.size(),
            "bridge mode must see exactly the alerts the file path sees");
        for (int i = 0; i < directAlerts.size(); i++) {
            assertEquals(directAlerts.get(i).type, bridgeAlerts.get(i).type);
            assertEquals(directAlerts.get(i).playerName, bridgeAlerts.get(i).playerName);
            assertEquals(directAlerts.get(i).description, bridgeAlerts.get(i).description);
            assertEquals(directAlerts.get(i).severity, bridgeAlerts.get(i).severity);
        }

        // The seeded patterns must actually fire through the bridge.
        assertTrue(bridgeAlerts.stream().anyMatch(a -> a.type == FraudDetector.FraudAlert.Type.RAPID_WEALTH_GAIN
                && "Alice".equals(a.playerName)),
            "rapid-wealth alert must fire through the bridge");
        assertTrue(bridgeAlerts.stream().anyMatch(a -> a.type == FraudDetector.FraudAlert.Type.CIRCULAR_TRADING),
            "circular 2-loop must fire through the bridge");
    }

    @Test
    void brokenLedgerSourceFailsOpenWithNoException() throws Exception {
        Path economyDb = seedLedger(shopRow(System.currentTimeMillis(), "alice", "Alice", 1.00));
        FraudDetector detector = new FraudDetector(null, economyDb.toString());
        detector.setLedgerAccess(CoreLedgerAccess.forConnectionProvider(work -> {
            throw new SQLException("simulated core outage");
        }));

        List<FraudDetector.FraudAlert> alerts = detector.runAllChecks();

        assertNotNull(alerts, "a failed scan must still return a list");
        assertTrue(alerts.isEmpty(), "a failed scan must degrade to an empty alert list");
        assertEquals(0, detector.getHighSeverityCount());
    }

    @Test
    void missingFileStillFailsOpenInDirectMode() throws Exception {
        // Bridge null + nonexistent economy.db: DirectDb refuses to create the
        // file, every check degrades, runAllChecks returns an empty list.
        Path missing = Files.createTempDirectory("solidus-fraud-missing").resolve("economy.db");
        FraudDetector detector = new FraudDetector(null, missing.toString());
        List<FraudDetector.FraudAlert> alerts = detector.runAllChecks();
        assertNotNull(alerts);
        assertTrue(alerts.isEmpty());
    }
}
