package com.solidus.analytics.cloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit B-2 regression tests: the agent-side G3 idempotency window must be
 * an ATOMIC claim (INSERT OR IGNORE linearization), not the historical
 * check-then-act that two racing frames - or a relay crash-re-forward - could
 * both pass, double-executing financial commands.
 */
@DisplayName("CloudAgentStore idempotency (G3 / audit B-2)")
class CloudAgentStoreTest {

    private Path configDir;
    private CloudAgentStore store;

    @BeforeEach
    void setUp() throws Exception {
        configDir = Files.createTempDirectory("solidus-cloud-store-");
        store = new CloudAgentStore(configDir);
        store.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.shutdown();
        Files.walk(configDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(p -> p.toFile().delete());
    }

    @Test
    @DisplayName("first claim wins, concurrent second claim observes pending")
    void firstClaimIsAtomic() {
        CloudAgentStore.Claim first = store.claimIdempotent("idem-1", "econ.grant");
        assertTrue(first.claimed(), "the first claimant owns execution");

        // A racing frame with the same idemKey must NOT be able to claim.
        CloudAgentStore.Claim second = store.claimIdempotent("idem-1", "econ.grant");
        assertFalse(second.claimed());
        assertEquals("pending", second.existingStatus());
    }

    @Test
    @DisplayName("terminal outcome is finalized and replayed to duplicates, never re-executed")
    void terminalResultIsReplayed() {
        store.claimIdempotent("idem-2", "econ.grant");
        store.finalizeIdempotent("idem-2", "econ.grant", "applied", "{\"ok\":true,\"balanceC\":420}");

        CloudAgentStore.Claim dup = store.claimIdempotent("idem-2", "econ.grant");
        assertFalse(dup.claimed(), "duplicate must not re-execute");
        assertEquals("applied", dup.existingStatus());
        assertNotNull(dup.existingResultJson());
        assertTrue(dup.existingResultJson().contains("420"));
        assertEquals("{\"ok\":true,\"balanceC\":420}", store.findIdempotent("idem-2"));
    }

    @Test
    @DisplayName("rejected outcomes are also idempotent (same key, same answer)")
    void rejectedOutcomeIsStored() {
        store.claimIdempotent("idem-3", "econ.grant");
        store.finalizeIdempotent("idem-3", "econ.grant", "rejected", "{\"code\":\"E_STATE\"}");

        CloudAgentStore.Claim dup = store.claimIdempotent("idem-3", "econ.grant");
        assertFalse(dup.claimed());
        assertEquals("rejected", dup.existingStatus());
    }

    @Test
    @DisplayName("a stale pending claim (crashed process) becomes reclaimable after the window")
    void stalePendingIsReclaimable() throws Exception {
        CloudAgentStore.Claim first = store.claimIdempotent("idem-4", "econ.grant");
        assertTrue(first.claimed());
        // Simulate a crash: the claim stays 'pending' forever. Age it past the
        // stale window with a second connection to the same SQLite file.
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + configDir.resolve("cloud.db").toAbsolutePath())) {
            try (var ps = c.prepareStatement(
                    "UPDATE cloud_idempotency SET ts = ? WHERE idem_key = ?")) {
                ps.setLong(1, System.currentTimeMillis() - CloudAgentStore.PENDING_STALE_MS - 1000L);
                ps.setString(2, "idem-4");
                ps.executeUpdate();
            }
        }
        CloudAgentStore.Claim reclaimer = store.claimIdempotent("idem-4", "econ.grant");
        assertTrue(reclaimer.claimed(), "a stale pending claim must be reclaimable");
    }

    @Test
    @DisplayName("claims survive a restart (persistent window, PROTOCOL §8)")
    void claimsSurviveRestart() throws Exception {
        store.claimIdempotent("idem-5", "econ.grant");
        store.finalizeIdempotent("idem-5", "econ.grant", "applied", "{\"ok\":true}");
        store.shutdown();

        CloudAgentStore reopened = new CloudAgentStore(configDir);
        reopened.initialize();
        try {
            CloudAgentStore.Claim dup = reopened.claimIdempotent("idem-5", "econ.grant");
            assertFalse(dup.claimed(), "restart must not lose the executed key");
            assertEquals("applied", dup.existingStatus());
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    @DisplayName("oversized wire fields are truncated before persistence (audit B-11)")
    void oversizedFieldsAreTruncated() {
        String hugeKey = "k".repeat(5000);
        store.claimIdempotent(hugeKey, "econ.grant");
        // The claim is stored under the truncated key, so the same huge key
        // finds the SAME row on its retry: truncation is idempotent.
        CloudAgentStore.Claim again = store.claimIdempotent(hugeKey, "econ.grant");
        assertFalse(again.claimed(), "retry of the same (truncated) key must not re-claim");
    }
}
