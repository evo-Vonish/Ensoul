package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.perception.region.RegionCognition;
import com.dwinovo.numen.core.perception.region.RegionKey;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Query tool (raw NumenTool): rebuild remembered cognition for a 16×64×16 region
 * from the append-only region event log (L2 regional observation). Given a world
 * coordinate it reports what the region <em>looked like when last observed</em> —
 * terrain, notable blocks, hazards — plus how long ago that was. The knowledge may
 * be stale: it is NOT a fresh scan, it is recall of what was seen. Omit the
 * coordinate to recall the region you are standing in.
 */
public final class RecallRegionTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();

    /** Boxed ints so an omitted / null coordinate means "my current region". */
    private record Args(Integer x, Integer y, Integer z) {}

    @Override
    public String name() {
        return "recall_region";
    }

    @Override
    public String description() {
        return "Recall what a region looked like when you last OBSERVED it (not a live scan — the "
                + "knowledge may be out of date). A region is a 16x64x16 cell; give any world coordinate "
                + "(x, y, z) inside it, or pass null for all three to recall the region you are standing "
                + "in. Returns the remembered terrain, notable blocks and hazards, and how many game days "
                + "ago it was seen. Says so if you have never observed that region.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableInteger("x", "World X inside the region, or null for your current region.")
                .nullableInteger("y", "World Y inside the region, or null for your current region.")
                .nullableInteger("z", "World Z inside the region, or null for your current region.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        String dim = self.level().dimension().identifier().toString();
        BlockPos base = (a.x() != null && a.y() != null && a.z() != null)
                ? new BlockPos(a.x(), a.y(), a.z())
                : self.blockPosition();
        RegionKey key = RegionKey.of(dim, base);
        reply.accept(RegionCognition.recall(self, key, self.level().getGameTime()));
    }
}
