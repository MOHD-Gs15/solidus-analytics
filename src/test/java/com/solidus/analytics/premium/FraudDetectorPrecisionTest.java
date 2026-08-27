package com.solidus.analytics.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the P2 precision defect: FraudDetector read REAL
 * income/amount columns through getLong(), silently truncating every decimal
 * S$ figure (12.75 -> 12) before the alert threshold comparisons ran.
 */
class FraudDetectorPrecisionTest {

    @Test
    void rapidWealthGainKeepsDecimalIncomePrecision() throws Exception {
        Path economyDb = Files.createTempDirectory("solidus-fraud").resolve("economy.db");
        long now = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + economyDb);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
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
                """);
            // Alice earns 300.25 S$ through fractional shop sales
            // (100.10 + 100.10 + 100.05) - truncation would have read 300.
            stmt.execute("INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, amount) VALUES ("
                + now + ", 'SHOP_SELL', 'alice', 'Alice', 100.10)");
            stmt.execute("INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, amount) VALUES ("
                + now + ", 'SHOP_SELL', 'alice', 'Alice', 100.10)");
            stmt.execute("INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, amount) VALUES ("
                + now + ", 'SHOP_SELL', 'alice', 'Alice', 100.05)");
            // Six background players earn 0.50 S$ each.
            for (int i = 1; i <= 6; i++) {
                stmt.execute("INSERT INTO transaction_log (timestamp, type, player_uuid, player_name, amount) VALUES ("
                    + now + ", 'SHOP_SELL', 'p" + i + "', 'P" + i + "', 0.50)");
            }
        }

        FraudDetector detector = new FraudDetector(null, economyDb.toString());
        detector.runAllChecks();

        List<FraudDetector.FraudAlert> alerts = detector.getRecentAlerts(50);

        // avg = 303.25 / 7 = 43.32 S$; 5x avg = 216.61 < Alice 300.25 -> flagged.
        // No other detector can fire here: 9 rows/minute < 30 frequency threshold,
        // and 10x avg size threshold (336.9) exceeds every single row (max 100.10).
        assertEquals(1, alerts.size());
        FraudDetector.FraudAlert alert = alerts.get(0);
        assertEquals(FraudDetector.FraudAlert.Type.RAPID_WEALTH_GAIN, alert.type);
        assertEquals("Alice", alert.playerName);
        assertTrue(alert.description.contains("300.25"),
            "alert text must keep the fractional income intact: " + alert.description);
        assertTrue(alert.description.contains("43.32"),
            "alert text must keep the fractional server average: " + alert.description);
    }
}
