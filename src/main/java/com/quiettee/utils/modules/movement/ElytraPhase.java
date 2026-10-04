package com.quiettee.utils.modules.movement;

import com.quiettee.utils.QuietteeUtils;
import com.quiettee.utils.events.PlayerPositionLookEvent;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ElytraPhase extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> phase = sgGeneral.add(new BoolSetting.Builder()
        .name("phase")
        .description("Tunnel through blocks when your flight path intersects them.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Double> stepLimit = sgGeneral.add(new DoubleSetting.Builder()
        .name("step-limit")
        .description("Maximum first-contact step in blocks. Servers accept up to about 0.24.")
        .defaultValue(0.24)
        .min(0.05)
        .max(0.249)
        .sliderRange(0.05, 0.249)
        .visible(phase::get)
        .build()
    );
    private final Setting<Boolean> chunkGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("chunk-guard")
        .description("Never tunnel toward unloaded chunks.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> correctionHold = sgGeneral.add(new IntSetting.Builder()
        .name("correction-hold")
        .description("Ticks to pause phasing after a server correction.")
        .defaultValue(20)
        .min(0)
        .max(100)
        .sliderRange(0, 60)
        .build()
    );
    private final Setting<Integer> correctionLimit = sgGeneral.add(new IntSetting.Builder()
        .name("correction-limit")
        .description("Corrections within five seconds before phasing falls back for ten seconds.")
        .defaultValue(3)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );
    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-info")
        .description("Report corrections and fallback in chat.")
        .defaultValue(true)
        .build()
    );

    private String status = "not gliding";
    private int holdUntilTick = -1000;
    private int fallbackUntilTick = -1000;
    private int corrections;
    private int lastCorrectionTick = -1000;
    private boolean ownedNoPhysics;

    public ElytraPhase() {
        super(QuietteeUtils.CATEGORY, "elytra-phase", "Tunnel through blocks while gliding on an elytra.");
    }

    @Override
    public void onActivate() {
        releaseNoPhysics();
        corrections = 0;
        holdUntilTick = fallbackUntilTick = -1000;
        lastCorrectionTick = -1000;
        status = "not gliding";
        info("Gliding phase ready. It engages when your flight path meets blocks.");
    }

    @Override
    public void onDeactivate() {
        releaseNoPhysics();
        status = "off";
    }

    @EventHandler(priority = EventPriority.LOWEST - 1)
    private void onPlayerMove(PlayerMoveEvent event) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (event.type != MoverType.SELF || !player.isFallFlying()) {
            releaseNoPhysics();
            status = "not gliding";
            return;
        }
        int now = player.tickCount;
        if (!phase.get()) {
            releaseNoPhysics();
            status = "phase off";
            return;
        }
        if (now < fallbackUntilTick) {
            releaseNoPhysics();
            status = "server rejected phase; retry in " + (fallbackUntilTick - now) / 20 + "s";
            return;
        }
        if (now < holdUntilTick) {
            releaseNoPhysics();
            status = "correction hold " + (holdUntilTick - now);
            return;
        }
        Vec3 movement = event.movement;
        if (!Double.isFinite(movement.x + movement.y + movement.z)) {
            releaseNoPhysics();
            return;
        }
        AABB box = player.getBoundingBox();
        AABB path = box.expandTowards(movement.x, movement.y, movement.z).contract(1.0E-7, 1.0E-7, 1.0E-7);
        boolean blocked = !mc.level.noCollision(player, path);
        boolean embedded = !mc.level.noCollision(player, box.contract(1.0E-7, 1.0E-7, 1.0E-7));
        if (!blocked && !embedded) {
            releaseNoPhysics();
            status = "gliding";
            return;
        }
        if (chunkGuard.get() && unloadedAhead(player, movement)) {
            releaseNoPhysics();
            ((IVec3) event.movement).meteor$set(0, movement.y, 0);
            status = "unloaded chunk ahead";
            return;
        }
        Vec3 capped = capStep(movement, stepLimit.get());
        ((IVec3) event.movement).meteor$set(capped.x, capped.y, capped.z);
        player.noPhysics = true;
        ownedNoPhysics = true;
        status = "phasing";
    }

    private static Vec3 capStep(Vec3 movement, double step) {
        double horizontal = Math.hypot(movement.x, movement.z);
        if (Math.abs(movement.y) >= 0.5) {
            if (horizontal <= step) return movement;
            double scale = step / horizontal;
            return new Vec3(movement.x * scale, movement.y, movement.z * scale);
        }
        double norm = movement.length();
        return norm <= step ? movement : movement.scale(step / norm);
    }

    private boolean unloadedAhead(LocalPlayer player, Vec3 movement) {
        for (int i = 0; i <= 4; i++) {
            double fraction = i / 4.0;
            int chunkX = Mth.floor(player.getX() + movement.x * fraction) >> 4;
            int chunkZ = Mth.floor(player.getZ() + movement.z * fraction) >> 4;
            if (!mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) return true;
        }
        return false;
    }

    private void releaseNoPhysics() {
        if (ownedNoPhysics && mc.player != null) mc.player.noPhysics = false;
        ownedNoPhysics = false;
    }

    @EventHandler(priority = EventPriority.LOWEST - 1)
    private void onTickPost(TickEvent.Post event) {
        releaseNoPhysics();
    }

    @EventHandler
    private void onCorrectionBefore(PlayerPositionLookEvent.Before event) {
        if (mc.player == null) return;
        releaseNoPhysics();
        int now = mc.player.tickCount;
        if (now - lastCorrectionTick > 100) corrections = 0;
        lastCorrectionTick = now;
        corrections++;
        holdUntilTick = now + correctionHold.get();
        if (corrections >= correctionLimit.get()) {
            fallbackUntilTick = now + 200;
            corrections = 0;
            status = "server rejected phase";
            if (chatInfo.get()) warning("Elytra Phase was rejected; phasing pauses for 10 seconds.");
        } else {
            status = "server correction";
            if (chatInfo.get()) info("Server corrected your glide (#%d); phasing pauses %d ticks.", corrections, correctionHold.get());
        }
    }

    @EventHandler
    private void onCorrectionAfter(PlayerPositionLookEvent.After event) {
        releaseNoPhysics();
    }

    @Override
    public String getInfoString() {
        return status;
    }
}
