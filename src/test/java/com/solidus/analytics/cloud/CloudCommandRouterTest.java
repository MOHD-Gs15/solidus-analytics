package com.solidus.analytics.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit regression tests for the agent-side allow-list router (G1..G3):
 * role floors re-checked on the agent, mandatory reason/idemKey, ATOMIC
 * idempotency (claim before execute, replay after - audit B-2) against a REAL
 * SQLite store, SAFE_NAME validation of every console target (B-4) and the
 * envelope-target binding for money commands (B-8).
 */
@DisplayName("CloudCommandRouter (G1-G3 / audit B-2,B-4,B-8)")
class CloudCommandRouterTest {

    private static final class RecordingSink implements CloudCommandRouter.Sink {
        volatile String status;
        volatile String code;
        volatile JsonObject data;
        volatile boolean duplicate;
        volatile int calls;

        @Override
        public void sendResult(String msgId, String rid, String cmd, String target, CloudCommandRouter.Actor actor,
                               String status, String code, JsonObject data, String error, long tookMs,
                               boolean duplicate) {
            ++this.calls;
            this.status = status;
            this.code = code;
            this.data = data;
            this.duplicate = duplicate;
        }

        @Override
        public void onVetoChanged() {
        }
    }

    private Path configDir;
    private CloudAgentStore store;
    private CloudAgent agent;
    private RecordingSink sink;
    private CloudCommandRouter router;

    @BeforeEach
    void setUp() throws Exception {
        configDir = Files.createTempDirectory("solidus-router-test-");
        store = new CloudAgentStore(configDir);
        store.initialize();
        // A real (unstarted) agent: constructors only assign fields, so this is
        // safe. The private store field is injected so route() can use the
        // REAL idempotency code path (agent.server() stays null: every test
        // here stops before SERVER-affinity dispatch).
        agent = new CloudAgent(null, configDir, configDir, "eco.db", "auc.db", "ana.db");
        Field storeField = CloudAgent.class.getDeclaredField("store");
        storeField.setAccessible(true);
        storeField.set(agent, store);
        sink = new RecordingSink();
        router = new CloudCommandRouter(agent, sink);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.shutdown();
        Files.walk(configDir)
            .sorted((a, b) -> b.compareTo(a))
            .forEach(p -> p.toFile().delete());
    }

    private static JsonObject frame(String cmd, String target, String reason, String role, String idemKey) {
        JsonObject msg = new JsonObject();
        msg.addProperty("id", "m-test");
        msg.addProperty("t", "cmd");
        msg.addProperty("cmd", cmd);
        msg.addProperty("target", target);
        msg.addProperty("reason", reason);
        JsonObject actor = new JsonObject();
        actor.addProperty("uid", "u-1");
        actor.addProperty("name", "tester");
        actor.addProperty("role", role);
        msg.add("actor", actor);
        if (idemKey != null) {
            msg.addProperty("idemKey", idemKey);
        }
        return msg;
    }

    // ---- allow-list + role floor -----------------------------------------

    @Test
    @DisplayName("unknown command ids are rejected with E_UNKNOWN_CMD (G1)")
    void unknownCommandRejected() {
        router.route("m-test", frame("console.exec", "", "", "owner", null));
        assertEquals("rejected", sink.status);
        assertEquals("E_UNKNOWN_CMD", sink.code);
    }

    @Test
    @DisplayName("role floor is re-checked agent-side (viewer cannot stop the server)")
    void roleFloorEnforced() {
        router.route("m-test", frame("server.stop", "", "because", "viewer", null));
        assertEquals("rejected", sink.status);
        assertEquals("E_ROLE", sink.code);
    }

    @Test
    @DisplayName("reason is mandatory for W2/D (G2)")
    void reasonMandatoryForW2() {
        router.route("m-test", frame("player.ban", "Notch", "", "admin", null));
        assertEquals("rejected", sink.status);
        assertEquals("E_ARGS", sink.code);
    }

    @Test
    @DisplayName("idemKey is mandatory for financial commands (G3)")
    void idemKeyMandatory() {
        router.route("m-test", frame("econ.grant", "Notch", "refund", "admin", null));
        assertEquals("rejected", sink.status);
        assertEquals("E_ARGS", sink.code);
    }

    // ---- atomic idempotency (B-2), against the REAL store ----------------

    @Test
    @DisplayName("duplicate idemKey with a terminal result is replayed, never re-executed")
    void duplicateReplaysStoredResult() {
        // Simulate a prior execution: claim + finalize.
        store.claimIdempotent("idem-1", "econ.grant");
        store.finalizeIdempotent("idem-1", "econ.grant", "applied", "{\"ok\":true,\"balanceC\":777}");

        router.route("m-test", frame("econ.grant", "Notch", "refund", "admin", "idem-1"));

        assertEquals("applied", sink.status, "duplicates replay the first outcome");
        assertTrue(sink.duplicate, "replayed results are marked duplicate:true");
        assertNull(sink.code);
        assertTrue(sink.data.toString().contains("777"));
        // The handler never ran: had it run, Core absence would have produced
        // E_CORE_MISSING instead of the replayed balance.
        assertFalse(sink.data.toString().contains("E_CORE_MISSING"));
    }

    @Test
    @DisplayName("an in-flight idemKey is rejected with E_IDEM_DUP (no double execution)")
    void inFlightDuplicateRejected() {
        store.claimIdempotent("idem-2", "econ.grant");   // claimed, not finalized

        router.route("m-test", frame("econ.grant", "Notch", "refund", "admin", "idem-2"));
        assertEquals("rejected", sink.status);
        assertEquals("E_IDEM_DUP", sink.code);
    }

    @Test
    @DisplayName("store failures fail CLOSED for financial commands (B-2)")
    void storeErrorFailsClosed() throws Exception {
        // Close the underlying connection: every store call now errors and the
        // claim reports a store error -> the command must be refused.
        store.shutdown();
        router.route("m-test", frame("econ.grant", "Notch", "refund", "admin", "idem-3"));
        assertEquals("rejected", sink.status);
        assertEquals("E_EXEC", sink.code);
    }

    // ---- console target validation (B-4) ----------------------------------

    @Test
    @DisplayName("console targets must match ^[A-Za-z0-9_]{1,16}$ - '@a' is refused")
    void consoleTargetRejectsSelector() {
        assertThrows(CloudCommandRouter.CmdError.class, () -> CloudCommandRouter.consoleTarget("@a"));
        assertThrows(CloudCommandRouter.CmdError.class, () -> CloudCommandRouter.consoleTarget("@a[limit=1]"));
        assertThrows(CloudCommandRouter.CmdError.class, () -> CloudCommandRouter.consoleTarget("Name;stop"));
        assertThrows(CloudCommandRouter.CmdError.class, () -> CloudCommandRouter.consoleTarget(null));
        assertThrows(CloudCommandRouter.CmdError.class, () -> CloudCommandRouter.consoleTarget(""));
        assertThrows(CloudCommandRouter.CmdError.class, () -> CloudCommandRouter.consoleTarget("a".repeat(17)));
        assertThrows(CloudCommandRouter.CmdError.class, () -> CloudCommandRouter.consoleTarget("Notch "));
    }

    @Test
    @DisplayName("legitimate player names pass the console target check")
    void consoleTargetAcceptsSafeNames() throws Exception {
        assertEquals("Notch", CloudCommandRouter.consoleTarget("Notch"));
        assertEquals("player_42", CloudCommandRouter.consoleTarget("player_42"));
        assertEquals("a", CloudCommandRouter.consoleTarget("a"));
    }

    // ---- envelope-target binding (B-8) -------------------------------------

    @Test
    @DisplayName("args.target must equal the confirmed envelope target (identity confusion)")
    void confirmedTargetRejectsMismatch() {
        JsonObject ctxArgs = JsonParser.parseString("{\"target\":\"Steve\"}").getAsJsonObject();
        CloudCommandRouter.Ctx ctx = new CloudCommandRouter.Ctx("econ.grant", "Notch", "refund",
            new CloudCommandRouter.Actor("u-1", "tester", "admin"), 0L);
        CloudCommandRouter.CmdError e = assertThrows(CloudCommandRouter.CmdError.class,
            () -> CloudCommandRouter.confirmedTarget(ctx, ctxArgs, "target"));
        assertEquals("E_ARGS", e.code);
    }

    @Test
    @DisplayName("args.target equal to the envelope target is accepted")
    void confirmedTargetAcceptsMatch() throws Exception {
        JsonObject ctxArgs = JsonParser.parseString("{\"target\":\"Notch\"}").getAsJsonObject();
        CloudCommandRouter.Ctx ctx = new CloudCommandRouter.Ctx("econ.grant", "Notch", "refund",
            new CloudCommandRouter.Actor("u-1", "tester", "admin"), 0L);
        assertEquals("Notch", CloudCommandRouter.confirmedTarget(ctx, ctxArgs, "target"));
    }
}
