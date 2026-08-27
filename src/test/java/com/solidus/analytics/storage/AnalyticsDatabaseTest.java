package com.solidus.analytics.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnalyticsDatabaseTest {
    @Test
    void initializesAndRoundTripsSnapshot() throws Exception {
        Path dir = Files.createTempDirectory("solidus-analytics-db");
        AnalyticsDatabase database = new AnalyticsDatabase(dir.toString());
        database.initialize();
        assertTrue(database.isInitialized());

        AnalyticsDatabase.Snapshot expected = new AnalyticsDatabase.Snapshot(
            1_700_000_000_000L, "TEST", 10_000L, 4, 0.25, 2_500L, 2_000L,
            0.40, 10_000L, 3, 5_000L);
        database.insertSnapshot(expected);
        AnalyticsDatabase.Snapshot actual = database.getLatestSnapshot();

        assertNotNull(actual);
        assertEquals(expected.timestamp(), actual.timestamp());
        assertEquals(expected.snapshotType(), actual.snapshotType());
        assertEquals(expected.totalWealth(), actual.totalWealth());
        assertEquals(expected.playerCount(), actual.playerCount());
        assertEquals(expected.giniCoefficient(), actual.giniCoefficient());
        assertEquals(expected.auctionTotalValue(), actual.auctionTotalValue());
        database.shutdown();
    }
}
