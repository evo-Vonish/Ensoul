import com.dwinovo.numen.mcp.journal.JournalEvent;
import com.dwinovo.numen.mcp.journal.McpJournal;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.UUID;

/** Standalone behavioural check for the journal. No JUnit, no Minecraft — that is the point. */
public final class JournalCheck {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name + "  -- " + detail); }
    }

    static JsonObject body(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    static JsonObject urgentBody() {
        JsonObject o = new JsonObject();
        o.addProperty("urgent", true);
        o.addProperty("text", "creeper 8 blocks north");
        return o;
    }

    public static void main(String[] args) throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        // ---- 1. empty journal
        McpJournal j1 = new McpJournal(8);
        var b = j1.since(0, McpJournal.Filter.ALL, 64);
        check("empty journal yields no events", b.events().isEmpty(), "got " + b.events().size());
        check("empty journal cursor stays 0", b.cursor() == 0, "cursor=" + b.cursor());
        check("empty journal lastSeq 0", j1.lastSeq() == 0, "" + j1.lastSeq());

        // ---- 2. seq assignment + basic read
        var e1 = j1.append(JournalEvent.Type.PERCEPTION, "observed", 100, alice, null, body("t", "a"));
        var e2 = j1.append(JournalEvent.Type.PERCEPTION, "observed", 101, alice, null, body("t", "b"));
        var e3 = j1.append(JournalEvent.Type.PERCEPTION, "observed", 102, bob, null, body("t", "c"));
        check("seq starts at 1", e1.seq() == 1, "" + e1.seq());
        check("seq is monotonic", e2.seq() == 2 && e3.seq() == 3, e2.seq() + "," + e3.seq());
        b = j1.since(0, McpJournal.Filter.ALL, 64);
        check("since(0) returns all", b.events().size() == 3, "" + b.events().size());
        check("cursor = last seq", b.cursor() == 3, "" + b.cursor());

        // ---- 3. cursor semantics: strictly after
        b = j1.since(2, McpJournal.Filter.ALL, 64);
        check("since(2) returns only seq 3", b.events().size() == 1 && b.events().get(0).seq() == 3,
                "" + b.events().size());

        // ---- 4. companion filter
        b = j1.since(0, new McpJournal.Filter(Set.of(alice), Set.of()), 64);
        check("companion filter keeps only alice", b.events().size() == 2, "" + b.events().size());
        check("filtered cursor still advances past filtered-out events", b.cursor() == 3, "" + b.cursor());

        // ---- 5. type filter
        b = j1.since(0, new McpJournal.Filter(Set.of(), Set.of(JournalEvent.Type.DEATH)), 64);
        check("type filter excludes non-matching", b.events().isEmpty(), "" + b.events().size());

        // ---- 6. ring wrap + gap
        McpJournal j2 = new McpJournal(4);
        for (int i = 0; i < 10; i++) {
            j2.append(JournalEvent.Type.PERCEPTION, "observed", i, alice, null, body("i", "" + i));
        }
        check("lastSeq after 10 appends", j2.lastSeq() == 10, "" + j2.lastSeq());
        check("oldestSeq after wrap", j2.oldestSeq() == 7, "" + j2.oldestSeq());
        b = j2.since(0, McpJournal.Filter.ALL, 64);
        check("stale cursor reports GAP", McpJournal.Reason.GAP.equals(b.reason()), b.reason());
        check("GAP is the first event", !b.events().isEmpty()
                && JournalEvent.Type.GAP.equals(b.events().get(0).type()),
                b.events().isEmpty() ? "empty" : b.events().get(0).type());
        long missed = b.events().get(0).body().get("missed").getAsLong();
        check("GAP reports correct missed count (6)", missed == 6, "" + missed);
        check("GAP seq precedes oldest survivor",
                b.events().get(0).seq() == j2.oldestSeq() - 1, "" + b.events().get(0).seq());
        check("GAP batch then carries survivors", b.events().size() == 5, "" + b.events().size());

        // ---- 7. fresh cursor after gap does not re-gap
        b = j2.since(b.cursor(), McpJournal.Filter.ALL, 64);
        check("cursor from gap batch is clean", !McpJournal.Reason.GAP.equals(b.reason()), b.reason());

        // ---- 8. max_events + more flag
        McpJournal j3 = new McpJournal(64);
        for (int i = 0; i < 20; i++) {
            j3.append(JournalEvent.Type.PERCEPTION, "observed", i, alice, null, body("i", "" + i));
        }
        b = j3.since(0, McpJournal.Filter.ALL, 5);
        check("respects maxEvents", b.events().size() == 5, "" + b.events().size());
        check("signals more remaining", b.more(), "more=false");
        b = j3.since(b.cursor(), McpJournal.Filter.ALL, 64);
        check("continues from capped cursor", b.events().size() == 15, "" + b.events().size());

        // ---- 9. wake accumulation: ordinary events must NOT wake a task-waiter
        McpJournal j4 = new McpJournal(64);
        j4.append(JournalEvent.Type.PERCEPTION, "observed", 1, alice, null, body("t", "noise"));
        var wakeTask = new McpJournal.Wake(Set.of("t-7"), false, false);
        b = j4.await(0, McpJournal.Filter.ALL, 64, 0, wakeTask);
        check("ordinary event does not satisfy a task wake", b.events().isEmpty(), "" + b.events().size());
        check("non-waking read does not advance cursor", b.cursor() == 0, "" + b.cursor());

        // ---- 10. terminal task event wakes, and delivers the accumulated backlog
        j4.append(JournalEvent.Type.TASK_COMPLETED, "observed", 2, alice, "t-7", body("r", "ok"));
        b = j4.await(0, McpJournal.Filter.ALL, 64, 0, wakeTask);
        check("terminal task event wakes", McpJournal.Reason.TASK.equals(b.reason()), b.reason());
        check("wake delivers accumulated backlog too", b.events().size() == 2, "" + b.events().size());

        // ---- 11. TASK_DISPATCHED must not wake (it is the agent's own echo)
        McpJournal j5 = new McpJournal(64);
        j5.append(JournalEvent.Type.TASK_DISPATCHED, "observed", 1, alice, "t-9", body("x", "y"));
        b = j5.await(0, McpJournal.Filter.ALL, 64, 0, new McpJournal.Wake(Set.of("t-9"), false, false));
        check("TASK_DISPATCHED does not wake its own waiter", b.events().isEmpty(), "" + b.events().size());

        // ---- 12. urgent wake
        McpJournal j6 = new McpJournal(64);
        j6.append(JournalEvent.Type.PERCEPTION, "observed", 1, alice, null, body("t", "calm"));
        j6.append(JournalEvent.Type.PERCEPTION, "observed", 2, alice, null, urgentBody());
        b = j6.await(0, McpJournal.Filter.ALL, 64, 0, new McpJournal.Wake(Set.of(), true, false));
        check("urgent body wakes an urgent waiter", McpJournal.Reason.URGENT.equals(b.reason()), b.reason());

        // ---- 13. reflex is urgent by definition
        McpJournal j7 = new McpJournal(64);
        j7.append(JournalEvent.Type.REFLEX, "observed", 1, alice, null, body("what", "lava retreat"));
        b = j7.await(0, McpJournal.Filter.ALL, 64, 0, new McpJournal.Wake(Set.of(), true, false));
        check("REFLEX counts as urgent", McpJournal.Reason.URGENT.equals(b.reason()), b.reason());

        // ---- 14. control wake
        McpJournal j8 = new McpJournal(64);
        j8.append(JournalEvent.Type.LEASE_LOST, "system", 1, alice, null, body("why", "expired"));
        b = j8.await(0, McpJournal.Filter.ALL, 64, 0, new McpJournal.Wake(Set.of(), false, true));
        check("LEASE_LOST wakes a control waiter", McpJournal.Reason.CONTROL.equals(b.reason()), b.reason());

        // ---- 15. timeout returns empty, unchanged cursor, and is NOT an error
        McpJournal j9 = new McpJournal(8);
        long t0 = System.currentTimeMillis();
        b = j9.await(0, McpJournal.Filter.ALL, 64, 300, McpJournal.Wake.ANY);
        long elapsed = System.currentTimeMillis() - t0;
        check("timeout returns empty", b.events().isEmpty(), "" + b.events().size());
        check("timeout reason", McpJournal.Reason.TIMEOUT.equals(b.reason()), b.reason());
        check("timeout preserves cursor", b.cursor() == 0, "" + b.cursor());
        check("timeout actually waited (>=250ms)", elapsed >= 250, elapsed + "ms");

        // ---- 16. a concurrent append wakes a parked reader promptly
        McpJournal j10 = new McpJournal(8);
        Thread writer = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            j10.append(JournalEvent.Type.PERCEPTION, "observed", 1, alice, null, body("t", "late"));
        });
        writer.start();
        long t1 = System.currentTimeMillis();
        b = j10.await(0, McpJournal.Filter.ALL, 64, 5000, McpJournal.Wake.ANY);
        long waited = System.currentTimeMillis() - t1;
        writer.join();
        check("parked reader woken by append", b.events().size() == 1, "" + b.events().size());
        check("woken promptly, not at timeout", waited < 2000, waited + "ms");

        // ---- 17. journal-wide events survive a companion filter
        McpJournal j11 = new McpJournal(8);
        j11.append(JournalEvent.Type.USAGE, "system", 1, null, null, body("tokens", "500"));
        b = j11.since(0, new McpJournal.Filter(Set.of(alice), Set.of()), 64);
        check("companion filter keeps journal-wide events", b.events().size() == 1, "" + b.events().size());

        // ---- 18. wire form omits absent optionals
        JsonObject wire = e1.toJson();
        check("wire has seq/type/body", wire.has("seq") && wire.has("type") && wire.has("body"), wire.toString());
        check("wire omits absent taskId", !wire.has("taskId"), wire.toString());
        check("wire includes companion when present", wire.has("companion"), wire.toString());

        System.out.println();
        System.out.println("  " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
