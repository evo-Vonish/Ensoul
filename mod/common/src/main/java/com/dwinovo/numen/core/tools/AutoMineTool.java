package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): gather blocks by type and quantity. */
public final class AutoMineTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionTools impl = new BlockActionTools();

    private record Args(List<String> block_ids, int count, Integer radius, Boolean vein) {}

    @Override
    public String name() {
        return "auto_mine";
    }

    @Override
    public String description() {
        return "Gather blocks by type and quantity. Give the block id(s) and how many you want — the "
                + "entity finds the nearest ones and travels to each with full terrain-traversing "
                + "navigation: it digs tunnels to reach buried ores, pillars up to blocks high on cliffs, "
                + "and bridges gaps with cobblestone/dirt from its own inventory, all automatically — then "
                + "mines them and repeats until it has gathered `count` of the resulting ITEMS or none "
                + "remain nearby. count is items, not blocks: a block can drop several (redstone_ore → ~4 "
                + "redstone), so count:10 redstone mines only ~3 ore. It counts only NEW items gained, on "
                + "top of what you already carry. You do NOT provide coordinates, call move_to, or pre-clear "
                + "a path; carrying some cobblestone/dirt helps it cross terrain. Include all variants of a "
                + "resource in block_ids (e.g. iron_ore AND deepslate_iron_ore). Optional radius caps how "
                + "far to look (fixed spherical radius, default 48; it does not auto-expand). Optional vein "
                + "(default false) switches the semantics: when true, reaching the first block commits the "
                + "entity to the whole CONNECTED same-type vein (26-neighbour flood-fill; deepslate and stone "
                + "variants count as one family) and mines it to exhaustion, with count demoted to an "
                + "upper-bound safety cap rather than a target — use it to strip a deposit clean (e.g. a full "
                + "diamond vein) instead of gathering an exact quantity. Returns the actual number gathered, "
                + "which may be less than requested if the deposit runs out. You must hold a tool that can "
                + "harvest the target: mining a block your main-hand tool can't harvest fails up front and "
                + "tells you the minimum tier required (e.g. iron_ore needs a stone pickaxe). Equip the right "
                + "pickaxe/axe/shovel yourself first (equip_item) — check get_self_status for your main hand.";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> parameterSchema() {
        Map<String, Object> schema = Schema.object()
                .stringArray("block_ids", "Namespaced block id(s) to gather; include all variants.", 1)
                .integer("count", "How many ITEMS to gather (not blocks) — a block may drop several, and it "
                        + "counts only items gained on top of what you already hold. In vein mode this is only "
                        + "an upper-bound safety cap.", 1, 256)
                .optionalInteger("radius", "Optional max search radius in blocks (fixed, default 48).", 1, 96)
                .build();
        // Append an OPTIONAL `vein` boolean without touching the shared Schema builder (which has no
        // optionalBoolean): add the property but leave it out of `required`, so a missing value binds null.
        Map<String, Object> vein = new LinkedHashMap<>();
        vein.put("type", "boolean");
        vein.put("description", "Optional (default false). true: after reaching the first block, mine the "
                + "whole connected same-type vein to exhaustion (count becomes an upper-bound cap). false: "
                + "gather exactly `count` items, then stop.");
        ((Map<String, Object>) schema.get("properties")).put("vein", vein);
        return schema;
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        TaskRecord rec = impl.autoMine(a.block_ids(), a.count(), a.radius(), ctx(toolCallId, companion));
        // vein is an optional add-on the shared BlockActionTools.autoMine builder doesn't take; set it
        // on the record here (once, before the task is enqueued/started). Absent ⇒ false ⇒ default mode.
        if (rec instanceof MineBlockTaskRecord mine && a.vein() != null && a.vein()) {
            mine.setVein(true);
        }
        enqueue(companion, rec);
    }
}
