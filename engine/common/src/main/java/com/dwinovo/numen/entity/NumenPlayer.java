package com.dwinovo.numen.entity;

import com.dwinovo.numen.entity.look.LookController;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The companion body: a server-side fake {@link ServerPlayer}. Replaces the old
 * custom {@code NumenEntity} Mob so the companion is a first-class player —
 * native interaction/combat code paths (universal mod compatibility), its own
 * player inventory, and free chunk loading + playerdata persistence by virtue of
 * being a list-resident player.
 *
 * <h2>Identity &amp; ownership</h2>
 * Created by {@link CompanionFactory} with a stable per-companion UUID (carried
 * in the {@link GameProfile}); the enumerable index lives in
 * {@link CompanionRegistry}. Unlike the Mob, a fake player cannot carry custom
 * {@code SynchedEntityData}, so the owner is a plain server-side field persisted
 * to the companion's own playerdata {@code .dat} via
 * {@link #addAdditionalSaveData}. Owner checks are UUID comparisons — never
 * vanilla {@code isOwnedBy} (which resolves through a level and breaks across
 * dimensions).
 */
public final class NumenPlayer extends ServerPlayer {

    private static final String NBT_KEY_OWNER = "NumenOwner";

    /** Owner's player UUID. Null only transiently before the first assignment. */
    private UUID ownerUuid;

    /** Latched once we've handled this body's death, so the post-death routine runs exactly once. */
    private boolean deathHandled;

    /**
     * The REAL localized death message ("Fenn被僵尸杀死了"), snapshotted in {@link #die} on the
     * damage tick. Needed because the death poll in {@link #tick} reads the cause on a LATER
     * tick, by which point the fake player's combat tracker has degraded to the generic
     * message ("Fenn死了") — which a pack producer's cause-normalizer then reduced to the
     * single character "死" (field evidence: "你已第 3 次死于死", useless as a stop-loss key).
     * Transient, body-scoped; consumed (cleared) by {@link #consumeLastDeathMessage}.
     */
    private String lastDeathMessage;

    /**
     * Last amplifier seen per active effect, so the {@link #onEffectUpdated} perception seam can
     * tell a real "effect got stronger" change from the harmless per-tick re-syncs vanilla fires
     * for the same instance (duration ticking down, periodic refresh every 600 ticks). Transient,
     * body-scoped state (dies with the body — no cleanup needed, no persistence).
     */
    private final Map<Holder<MobEffect>, Integer> lastEffectAmplifier = new HashMap<>();

    /**
     * The lively-look engine: the per-tick kinematic writer that turns a gaze intent into smooth,
     * owl-safe head/body motion. It is ticked at the tail of {@link #tick()} — the only window where
     * an independent {@code yHeadRot} survives {@code Player.aiStep}'s per-tick {@code yHeadRot = yRot}
     * overwrite and still ships to clients. Intent is fed by the tool pack's attention brain, and
     * hard-aim / locomotion yielding rides through {@code InputDriver} automatically (see
     * {@link LookController}). Body-scoped, dies with the body.
     */
    private final LookController look;

    public NumenPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                        ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
        this.look = new LookController(this);
    }

    /** The lively-look engine that owns this body's head/body/pitch smoothing. Never null. */
    public LookController getLook() {
        return look;
    }

    /** The loaded companion body with this UUID, or {@code null} if not spawned. */
    public static NumenPlayer findByUuid(MinecraftServer server, UUID uuid) {
        return server.getPlayerList().getPlayer(uuid) instanceof NumenPlayer ap ? ap : null;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    /** Cross-dimension safe owner check — UUID comparison, not level-scoped lookup. */
    public boolean isOwnedByPlayer(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    /** The owner as an online player, server-wide; null when offline. */
    public ServerPlayer resolveOwnerPlayer() {
        return ownerUuid == null ? null : level().getServer().getPlayerList().getPlayer(ownerUuid);
    }


    /** True if {@code item} sits anywhere in the inventory (hotbar/main/offhand all count). */
    public boolean ensureInInventory(Item item) {
        var inv = getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    /**
     * Hold the item in inventory slot {@code slot} in the main hand the way a real player
     * does — a hotbar slot is simply SELECTED (number-key); a main-inventory slot is SWAPPED
     * into the currently selected hotbar slot (item-conserving). This is the only correct way
     * to "switch to hand": calling {@code setItemInHand(MAIN_HAND, stack)} overwrites the held
     * item (losing it) and aliases ONE {@link net.minecraft.world.item.ItemStack} across two
     * slots, which corrupts the inventory once the stack is consumed. No-op for {@code slot < 0}.
     */
    public void holdInHand(int slot) {
        if (slot < 0) {
            return;
        }
        var inv = getInventory();
        if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) {
            inv.setSelectedSlot(slot);
            return;
        }
        int selected = inv.getSelectedSlot();
        net.minecraft.world.item.ItemStack held = inv.getItem(selected);
        inv.setItem(selected, inv.getItem(slot));
        inv.setItem(slot, held);
    }

    // ---- server tick (Carpet's EntityPlayerMPFake trick) ----

    /**
     * Drive the body's own movement physics. A real {@link ServerPlayer} runs
     * {@code travel} (against {@code zza}/{@code xxa}), food, air and pose inside
     * {@link #doTick()}, which the network layer invokes via
     * {@code connection.tick()}. A fake player's connection is a no-op, so
     * {@code doTick()} never fires and the body would only ever turn (a direct
     * {@code setYRot} write) without walking. The entity system already calls
     * {@code super.tick()} (menus / container / position sync), so we add the
     * missing {@code doTick()} movement pass here — exactly as Carpet's
     * {@code EntityPlayerMPFake.tick()} does. Every 10 ticks we resync the
     * connection position and let chunk loading follow the body so it never
     * walks out of its loaded area.
     */
    @Override
    public void tick() {
        // A fake player isn't auto-removed on death (no client to send a respawn packet), so it would
        // sit at 0 HP forever. Detect death once, hand off to the recoverable-death routine (stop the
        // brain, schedule a respawn at the owner), and skip the normal movement/AI tick for this corpse.
        if (!deathHandled && (getHealth() <= 0.0f || isDeadOrDying())) {
            deathHandled = true;
            Companions.onDeath(this);
            return;
        }
        if (level() instanceof ServerLevel sl && sl.getGameTime() % 10 == 0) {
            this.connection.resetPosition();
            sl.getChunkSource().move(this);
        }
        super.tick();
        try {
            this.doTick();
        } catch (Exception ignored) {
            // mirrors Carpet — fake-connection internals can NPE on edge cases
        }
        // The lively-look engine writes LAST, on purpose. super.tick() ran Player.aiStep (which
        // hard-sets yHeadRot = yRot) and tickHeadTurn (which eases the body toward yRot); doTick()
        // ran travel. Only here — after aiStep, before the end-of-tick ServerEntity rotation
        // dispatch — does an independent head survive to the client. Writing it any earlier is
        // clobbered; writing it in the end-of-server-tick phase ships a tick late and is then
        // overwritten by the next aiStep. (26.1.2 rotation study §A4/§B2.)
        try {
            this.look.tick();
        } catch (Exception ignored) {
            // never let a look glitch break the body's tick
        }
    }

    /**
     * Snapshot the real per-source death message BEFORE vanilla death handling runs. Vanilla
     * {@code ServerPlayer.die} (verified in the 26.1.2 decompiled sources, ServerPlayer.java:927)
     * runs synchronously on the lethal-damage tick with the authoritative {@link DamageSource};
     * {@code source.getLocalizedDeathMessage(this)} (DamageSource.java:69) renders the same
     * contextual message a real player's death screen would show. The death poll at the top of
     * {@link #tick} — which always runs on a later tick — hands this to
     * {@link Companions#onDeath} instead of the by-then-generic combat-tracker message.
     */
    @Override
    public void die(DamageSource source) {
        this.lastDeathMessage = source.getLocalizedDeathMessage(this).getString();
        super.die(source);
    }

    /**
     * The death-tick message snapshot, cleared on read ({@code null} when death happened without
     * {@link #die} — e.g. a direct {@code setHealth(0)} — then the caller falls back to the
     * combat tracker exactly as before).
     */
    public String consumeLastDeathMessage() {
        String s = lastDeathMessage;
        lastDeathMessage = null;
        return s;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (ownerUuid != null) {
            output.store(NBT_KEY_OWNER, UUIDUtil.CODEC, ownerUuid);   // 1.21.6 ValueOutput IO
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(NBT_KEY_OWNER, UUIDUtil.CODEC).ifPresent(uuid -> this.ownerUuid = uuid);
    }

    // ---- perception seams (see PerceptionEvents) ----
    // These overrides are pure relays: capture the minimum raw data, run vanilla, fire the seam.
    // No thresholds / cooldowns / batching / wording — all of that lives in the tool pack.

    /**
     * Fire the hurt seam for a hit that actually landed. We snapshot health and block state at
     * entry (super may consume the block and drop health), then compare after super resolves the
     * hit. Fire only when damage was really applied (super returned {@code true} for a positive
     * amount) or health measurably dropped — this drops the immune / cancelled / zero-damage
     * no-ops that would otherwise spam the seam. Runs even for a lethal hit; death handling is
     * untouched (that stays with {@link Companions#onDeath} via the tick poll).
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        float healthBefore = getHealth();
        boolean blocking = isBlocking();
        boolean damaged = super.hurtServer(level, source, amount);
        float healthAfter = getHealth();
        if (healthAfter < healthBefore || (damaged && amount > 0.0f)) {
            PerceptionEvents.fireHurt(this, source, amount, healthAfter, blocking);
        }
        return damaged;
    }

    /** New effect applied — record its amplifier and fire the seam (added = true). */
    @Override
    protected void onEffectAdded(MobEffectInstance effect, Entity source) {
        super.onEffectAdded(effect, source);
        lastEffectAmplifier.put(effect.getEffect(), effect.getAmplifier());
        PerceptionEvents.fireEffectChange(this, effect, true);
    }

    /**
     * Existing effect re-applied. Vanilla calls this on every per-tick re-sync as well as on a
     * genuine re-apply, so fire the seam only when the amplifier actually changed (a stronger —
     * or weaker — effect), swallowing the duration-refresh churn. {@code effect} is already the
     * updated instance here (vanilla mutates it in place before calling this), so we compare
     * against the amplifier we last recorded.
     */
    @Override
    protected void onEffectUpdated(MobEffectInstance effect, boolean doRefreshAttributes, Entity source) {
        super.onEffectUpdated(effect, doRefreshAttributes, source);
        int amplifier = effect.getAmplifier();
        Integer previous = lastEffectAmplifier.get(effect.getEffect());
        if (previous == null || previous != amplifier) {
            lastEffectAmplifier.put(effect.getEffect(), amplifier);
            PerceptionEvents.fireEffectChange(this, effect, true);
        }
    }

    /** Effect(s) removed / expired — forget the tracked amplifier and fire the seam once each (added = false). */
    @Override
    protected void onEffectsRemoved(Collection<MobEffectInstance> effects) {
        super.onEffectsRemoved(effects);
        for (MobEffectInstance effect : effects) {
            lastEffectAmplifier.remove(effect.getEffect());
            PerceptionEvents.fireEffectChange(this, effect, false);
        }
    }

    /**
     * Fire the pickup seam. Verified against the caller ({@code ItemEntity.playerTouch}): the item
     * is already added to the inventory <em>before</em> this method is invoked, and the entity's
     * stack is restored to its picked-up count for a full pickup — so a copy taken at entry is the
     * authoritative snapshot of what was collected. We copy first (immutable, safe to hand to a
     * listener past the tick), run vanilla, then fire.
     */
    @Override
    public void onItemPickup(ItemEntity entity) {
        ItemStack picked = entity.getItem().copy();
        int count = picked.getCount();
        super.onItemPickup(entity);
        PerceptionEvents.fireItemPickup(this, picked, count);
    }
}
