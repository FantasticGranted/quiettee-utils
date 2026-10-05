package com.quiettee.utils.modules.combat;

import com.quiettee.utils.QuietteeUtils;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ElytraShot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Target Lock");
    private final SettingGroup sgFlight = settings.createGroup("Flight");

    private final Setting<Boolean> autoFire = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-fire").description("Draw and fire automatically at the locked target.")
        .defaultValue(true).build());
    private final Setting<Boolean> fullDraw = sgGeneral.add(new BoolSetting.Builder()
        .name("full-draw-only").description("Only redirect manual shots at full draw.")
        .defaultValue(true).build());
    private final Setting<Boolean> crystalGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("crystal-guard").description("Avoid firing when crystals or hazards threaten your path.")
        .defaultValue(true).build());
    private final Setting<Boolean> combatStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("combat-status").description("Show combat status above the hotbar.")
        .defaultValue(true).build());
    private final Setting<Boolean> hitInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("hit-info").description("Report confirmed arrow hits in chat.")
        .defaultValue(true).build());
    private final Setting<Boolean> chatInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("shot-info").description("Report locks and arrow spawn speed in chat.")
        .defaultValue(true).build());
    private final Setting<Boolean> debugFile = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-file").description("Write a log to .minecraft/elytra-shot.")
        .defaultValue(true).build());

    private final Setting<Boolean> autoAim = sgAim.add(new BoolSetting.Builder()
        .name("auto-aim").description("Aim shots for you. Camera stays free.")
        .defaultValue(true).build());
    private final Setting<Boolean> lockTarget = sgAim.add(new BoolSetting.Builder()
        .name("lock-target").description("Keep the target until you unlock it.")
        .defaultValue(true).build());
    private final Setting<Keybind> lockTargetKey = sgAim.add(new KeybindSetting.Builder()
        .name("lock-target-key").description("Lock or unlock the target nearest the crosshair.")
        .defaultValue(Keybind.none()).action(this::toggleTargetLock).build());
    private final Setting<Boolean> preferPlayers = sgAim.add(new BoolSetting.Builder()
        .name("prefer-players").description("Prefer players over mobs.")
        .defaultValue(true).build());
    private final Setting<Boolean> crosshairSelection = sgAim.add(new BoolSetting.Builder()
        .name("crosshair-selection").description("Lock the entity nearest your crosshair.")
        .defaultValue(true).build());
    private final Setting<Double> crosshairAngle = sgAim.add(new DoubleSetting.Builder()
        .name("crosshair-angle").description("Max crosshair angle for a new lock.")
        .defaultValue(30).range(1,89).sliderRange(1,60).visible(crosshairSelection::get).build());
    private final Setting<Double> targetRange = sgAim.add(new DoubleSetting.Builder()
        .name("target-range").description("Max distance for a new target.")
        .defaultValue(160).min(8).max(256).sliderRange(16, 200).build());
    private final Setting<Double> targetAngle = sgAim.add(new DoubleSetting.Builder()
        .name("target-angle").description("Max angle from your crosshair.")
        .defaultValue(100).min(5).max(180).sliderRange(10, 180).build());
    private final Setting<Double> extraLead = sgAim.add(new DoubleSetting.Builder()
        .name("extra-lead-ticks").description("Manual lead ticks for network delay.")
        .defaultValue(2).min(0).max(10).sliderRange(0, 6).build());
    private final Setting<Boolean> automaticLead = sgAim.add(new BoolSetting.Builder()
        .name("automatic-latency-lead").description("Estimate lead from your ping.")
        .defaultValue(true).build());
    private final Setting<Double> leadAdjustment = sgAim.add(new DoubleSetting.Builder()
        .name("lead-adjustment").description("Extra lead in ticks, positive or negative.")
        .defaultValue(0).range(-3,3).sliderRange(-3,3).build());
    private final Setting<Boolean> adaptivePrediction = sgAim.add(new BoolSetting.Builder()
        .name("adaptive-prediction").description("Predict movement from ping and server TPS.")
        .defaultValue(true).build());
    private final Setting<Boolean> showTarget = sgAim.add(new BoolSetting.Builder()
        .name("show-target").description("Outline the locked target.")
        .defaultValue(true).build());
    private final Setting<meteordevelopment.meteorclient.utils.render.color.SettingColor> targetColor = sgAim.add(new ColorSetting.Builder()
        .name("target-color").description("Target outline colour.")
        .defaultValue(new meteordevelopment.meteorclient.utils.render.color.SettingColor(255,205,40,255)).visible(showTarget::get).build());
    private final Setting<Integer> targetFill = sgAim.add(new IntSetting.Builder()
        .name("target-fill").description("Target fill opacity.")
        .defaultValue(55).range(0,180).sliderRange(0,120).visible(showTarget::get).build());

    private final Setting<Boolean> perch = sgFlight.add(new BoolSetting.Builder()
        .name("perch").description("Hold your altitude above the locked target while gliding.")
        .defaultValue(true).build());
    private final Setting<Double> hoverHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("hover-height").description("Height above the target to hold.")
        .defaultValue(20).min(5).max(30).sliderRange(8, 28).visible(perch::get).build());
    private final Setting<Double> followVerticalSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("follow-vertical-speed").description("Max vertical chase speed in blocks per tick.")
        .defaultValue(14.999).min(0.1).max(14.999).sliderRange(0.5, 14.999).visible(perch::get).build());
    private final Setting<Double> followMinVerticalSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("minimum-vertical-speed").description("Vertical speed before acceleration kicks in.")
        .defaultValue(7.999).min(0).max(14.999).sliderRange(0, 14.999).visible(perch::get).build());
    private final Setting<Integer> accelerationDelay = sgFlight.add(new IntSetting.Builder()
        .name("acceleration-delay").description("Ticks before vertical acceleration kicks in.")
        .defaultValue(1).min(0).sliderMax(100).visible(perch::get).build());
    private final Setting<Double> verticalAccelerationPlateau = sgFlight.add(new DoubleSetting.Builder()
        .name("vertical-acceleration-plateau").description("Vertical speed where acceleration tends to zero.")
        .defaultValue(14.999).min(0.01).max(14.999).sliderRange(0.5, 14.999).visible(perch::get).build());
    private final Setting<Double> verticalAccelerationStep = sgFlight.add(new DoubleSetting.Builder()
        .name("vertical-acceleration-step").description("How fast vertical speed ramps up.")
        .defaultValue(1.0).min(0.01).max(5).sliderRange(0.01, 3).visible(perch::get).build());

    private static final String[] CONFLICTS = { "boat-shot", "lance", "fusillade", "bow-spam", "bow-aimbot", "quiver", "shot-boost" };
    private final List<Module> paused = new ArrayList<>();
    private final BoatShotFeedback feedback = new BoatShotFeedback();
    private final BoatShotGuard guard = new BoatShotGuard();
    private final BoatShotPrediction prediction = new BoatShotPrediction();
    private final ConcurrentLinkedQueue<ClientboundAddEntityPacket> spawns = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Integer> arrows = new LinkedHashMap<>();
    private final Map<Integer, Boolean> seenArrows = new LinkedHashMap<>();
    private final AtomicBoolean corrected = new AtomicBoolean();

    private LocalPlayer player;
    private ClientLevel world;
    private ClientPacketListener network;
    private volatile Connection connection;
    private volatile int playerId = -1;
    private int tick, releaseTick = -100, nextAutoTick, lastWaitLog = -100, lastGuardRefresh = -100;
    private LivingEntity target, guardTarget;
    private Vec3 targetPosition, targetVelocity = Vec3.ZERO;
    private double predictedLead = 2;
    private boolean crystalsProtected, guardProtect;
    private double perchVy, appliedPerchVy, perchRamp;
    private int perchDelay;
    private boolean sendingRelease, suppressRelease, automaticReleasing, ownedAutoDraw, suppressAcquire;
    private String status = "waiting for session";
    private String aimFailure = "", obstruction = "";
    private String lastFlow = "";
    private PrintWriter log;
    private boolean logDirty;
    private Aim aimCache;
    private AimCacheKey aimCacheKey;
    private boolean aimCacheValid;
    private int aimCacheTick = -100;
    private record Aim(float yaw, float pitch) {}
    private record AimCacheKey(double mx, double my, double mz, Vec3 flight, double speed, AABB box, Vec3 tvel, double lead) {}

    public ElytraShot() {
        super(QuietteeUtils.CATEGORY, "elytra-shot", "Fire a bow while gliding; shots inherit your flight velocity and you perch above your lock.");
    }

    @Override public void onActivate() {
        resetSession();
        pauseConflicts();
        info("Shots inherit your flight velocity. Lock with the lock key or draw once; camera stays free. Hover holds you above the lock.");
    }

    @Override public void onDeactivate() {
        stopAutomaticDraw();
        abort("disabled", true);
        resetSession();
        restoreConflicts();
    }

    @EventHandler private void onGameLeft(GameLeftEvent event) { resetSession(); restoreConflicts(); }

    private boolean automaticFiring() { return autoFire.get(); }
    private boolean aimEnabled() { return autoAim.get(); }
    private boolean persistentLock() { return lockTarget.get(); }

    @EventHandler(priority = EventPriority.HIGHEST - 1)
    private void onTick(TickEvent.Pre event) {
        if (!sameSession()) {
            restoreConflicts();
            resetSession();
            if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
            player = mc.player;
            world = mc.level;
            network = mc.getConnection();
            connection = network.getConnection();
            playerId = player.getId();
            openLog();
        }
        tick++;
        if (corrected.getAndSet(false)) {
            stopAutomaticDraw();
            nextAutoTick = tick + 10;
            status = "server correction";
            record("CORRECTION tick=%d", tick);
        }
        observeArrows();
        feedback.tick(world, playerId, tick, () -> target,
            message -> { if (hitInfo.get()) info("%s", message); },
            message -> record("%s", message));
        if (!player.isAlive()) { status = "waiting"; flushLog(); return; }
        updateTarget();
        updatePerch();
        predictedLead = adaptivePrediction.get() || automaticLead.get()
            ? prediction.updateLead(PlayerUtils.getPing(), serverTps(), prediction.sampleAge(tick)) : extraLead.get();
        crystalsProtected = protectCrystals();
        if (tick - lastGuardRefresh >= 3 || target != guardTarget || crystalsProtected != guardProtect) refreshGuard();
        pauseConflicts();
        automaticDraw();
        if (!status.equals(lastFlow)) { record("FLOW tick=%d state=%s", tick, status); lastFlow = status; }
        flushLog();
    }

    private void updateTarget() {
        boolean drawing = drawingBow();
        if (drawing) suppressAcquire = false;
        boolean keep = persistentLock() || automaticFiring();
        if (!aimEnabled() || (!drawing && !keep)) {
            clearTarget();
            return;
        }
        if (!validTarget(target)) {
            if (target != null) stopAutomaticDraw();
            target = (drawing || keep) && !suppressAcquire ? chooseTarget() : null;
            targetPosition = target == null ? null : observedPosition(target);
            targetVelocity = Vec3.ZERO;
            prediction.resetMotion();
            acquiredTarget();
        }
        if (target != null) {
            targetPosition = observedPosition(target);
            double ratio = BoatShotPrediction.serverTicksPerClientTick(serverTps());
            var velocity = prediction.observePosition(tick, targetPosition.x, targetPosition.y, targetPosition.z,
                adaptivePrediction.get() ? (int) Math.ceil(3 / ratio) : 5);
            targetVelocity = new Vec3(velocity.x(), velocity.y(), velocity.z()).scale(1 / ratio);
        }
    }

    private void clearTarget() {
        target = null;
        targetPosition = null;
        targetVelocity = Vec3.ZERO;
        prediction.resetMotion();
    }

    private void updatePerch() {
        if (!perchActive()) {
            resetPerchRamp();
            perchVy = 0;
            return;
        }
        double dy = observedPosition(target).y + hoverHeight.get() - player.getY();
        if (Math.abs(dy) <= 1.0) {
            resetPerchRamp();
            perchVy = 0;
            return;
        }
        tickPerchRamp();
        perchVy = Mth.clamp(dy, -perchRamp, perchRamp);
    }

    private boolean perchActive() {
        return perch.get() && sameSession() && player.isAlive() && target != null && validTarget(target)
            && player.isFallFlying() && !player.getAbilities().flying;
    }

    private void resetPerchRamp() {
        perchRamp = Math.min(followMinVerticalSpeed.get(), followVerticalSpeed.get());
        perchDelay = 0;
    }

    private void tickPerchRamp() {
        if (perchDelay < accelerationDelay.get()) {
            if (perchDelay == 0) perchRamp = Math.min(followMinVerticalSpeed.get(), followVerticalSpeed.get());
            perchDelay++;
            return;
        }
        double plateau = verticalAccelerationPlateau.get();
        double gain = verticalAccelerationStep.get() * Math.max(0, plateau - perchRamp) / plateau;
        perchRamp = Math.min(perchRamp + gain, followVerticalSpeed.get());
    }

    private Vec3 plannedFlight() {
        Vec3 flight = player.getDeltaMovement();
        return perchVy == 0 ? flight : new Vec3(flight.x, perchVy, flight.z);
    }

    private Vec3 inheritedFlight() {
        Vec3 flight = player.getDeltaMovement();
        return appliedPerchVy == 0 ? flight : new Vec3(flight.x, appliedPerchVy, flight.z);
    }

    private void acquiredTarget() {
        if (target == null) return;
        if (automaticFiring() && !(player.getMainHandItem().getItem() instanceof BowItem)) {
            var bow = InvUtils.findInHotbar(stack -> stack.getItem() instanceof BowItem);
            if (bow.isHotbar()) InvUtils.swap(bow.slot(), false);
        }
        status = "locked: " + target.getName().getString();
    }

    private void automaticDraw() {
        if (!automaticFiring() || mc.gameMode == null) {
            status = aimEnabled() ? "auto-fire off" : "auto-aim off";
            stopAutomaticDraw();
            return;
        }
        String pause = !validTarget(target) ? "lock a target to begin"
            : mc.screen != null ? "paused: screen open"
            : !player.isFallFlying() ? "not gliding"
            : !(player.getMainHandItem().getItem() instanceof BowItem) ? "paused: select a bow"
            : tick < nextAutoTick ? "shot delay" : null;
        if (pause != null) {
            status = pause;
            stopAutomaticDraw();
            return;
        }
        if (!player.isUsingItem()) {
            if (player.getProjectile(player.getMainHandItem()).isEmpty() && !player.getAbilities().instabuild) {
                status = "out of arrows";
                return;
            }
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            ownedAutoDraw = player.isUsingItem();
            status = ownedAutoDraw ? "drawing bow" : "bow draw did not start";
            record("AUTO-DRAW tick=%d started=%b", tick, ownedAutoDraw);
            nextAutoTick = tick + 1;
            return;
        }
        if (player.getUsedItemHand() == InteractionHand.MAIN_HAND && player.getActiveItem().getItem() instanceof BowItem) ownedAutoDraw = true;
        Vec3 flight = player.getDeltaMovement();
        int required = BoatShotDamage.clientTicks(
            BoatShotDefense.drawTicks(target, player.getMainHandItem(), flight.length()), serverTps());
        status = "drawing " + player.getUseItemRemainingTicks() + "/" + required
            + " | HP " + String.format(Locale.ROOT, "%.1f", target.getHealth());
        if (tick - releaseTick < BoatShotDamage.clientTicks(10, serverTps())) return;
        if (player.getUsedItemHand() != InteractionHand.MAIN_HAND || !(player.getActiveItem().getItem() instanceof BowItem)
            || player.getUseItemRemainingTicks() < required) return;
        Aim aim = aimedShot(player.getUseItemRemainingTicks());
        if (aim == null) {
            status = aimFailure;
            if (tick - lastWaitLog >= 40) { record("AUTO-WAIT tick=%d reason=%s detail=%s", tick, status, obstruction); lastWaitLog = tick; }
            return;
        }
        if (!clearMuzzle(aim.yaw(), aim.pitch())) { status = "muzzle blocked"; return; }
        if (crystalGuard.get() && !guard.clear(world, player, plannedFlight(), crystalsProtected)) { status = "crystal or hazard danger"; return; }
        nextAutoTick = tick + 1;
        record("AUTO-RELEASE tick=%d draw=%d required=%d health=%.2f flight=%s yaw=%.2f pitch=%.2f",
            tick, player.getUseItemRemainingTicks(), required, target.getHealth(), flight, aim.yaw(), aim.pitch());
        automaticReleasing = true;
        try { mc.gameMode.releaseUsingItem(player); }
        finally { automaticReleasing = false; }
    }

    private Aim aimedShot(int age) {
        age = bowTicks(age);
        Vec3 flight = inheritedFlight();
        AABB box = observedTargetBox();
        Vec3 muzzle = player.getEyePosition();
        AimCacheKey key = new AimCacheKey(Math.rint(muzzle.x * 16) / 16, Math.rint(muzzle.y * 16) / 16,
            Math.rint(muzzle.z * 16) / 16, flight, BoatShotAim.bowSpeed(age), box, targetVelocity, aimLead());
        if (aimCacheValid && tick - aimCacheTick <= 2 && key.equals(aimCacheKey)) return aimCache;
        Aim result = solveAim(age, muzzle, flight, new double[] { .5, .65, .35 });
        aimCache = result; aimCacheKey = key; aimCacheValid = true; aimCacheTick = tick;
        return result;
    }

    private Aim solveAim(int age, Vec3 muzzle, Vec3 flight, double[] fractions) {
        AABB box = observedTargetBox();
        boolean solved = false;
        obstruction = "";
        for (double fraction : fractions) {
            Vec3 point = new Vec3((box.minX + box.maxX) / 2, box.minY + (box.maxY - box.minY) * fraction, (box.minZ + box.maxZ) / 2);
            Vec3 offset = point.add(targetVelocity.scale(aimLead())).subtract(muzzle);
            BoatShotAim.Solution solution = BoatShotAim.solveDiscrete(offset.x, offset.y, offset.z,
                targetVelocity.x, targetVelocity.y, targetVelocity.z,
                BoatShotAim.bowSpeed(age), flight.y, flight.x, flight.z, .01);
            if (solution == null) continue;
            solved = true;
            if (clearTrajectory(muzzle, solution, flight, age)) return new Aim(solution.yaw(), solution.pitch());
        }
        aimFailure = solved ? "arrow path blocked before target contact" : "no firing solution; change distance or angle";
        return null;
    }

    private boolean clearTrajectory(Vec3 muzzle, BoatShotAim.Solution aim, Vec3 flight, int age) {
        Vec3 velocity = Vec3.directionFromRotation(aim.yaw(), aim.pitch()).scale(BoatShotAim.bowSpeed(age)).add(flight);
        return BoatShotTrajectory.clearDiscrete(muzzle, velocity, aim.ticks(), observedTargetBox(), targetVelocity, aimLead(), (from, to) -> {
            var hit = world.clipIncludingBorder(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return true;
            obstruction = "block=" + hit.getBlockPos() + " state=" + world.getBlockState(hit.getBlockPos())
                + " at=" + hit.getLocation() + " target=" + target.getBoundingBox();
            return false;
        });
    }

    private boolean clearMuzzle(float yaw, float pitch) {
        Vec3 direction = Vec3.directionFromRotation(yaw, pitch);
        Vec3 eye = player.getEyePosition();
        return world.clipIncludingBorder(new ClipContext(eye, eye.add(direction.scale(2)),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    @EventHandler(priority = EventPriority.LOWEST - 1000)
    private void onSend(PacketEvent.Send event) {
        if (!mc.isSameThread() || !sameSession() || event.connection != connection || event.isCancelled()) return;
        if (sendingRelease || suppressRelease) return;
        if (!(event.packet instanceof ServerboundPlayerActionPacket action)
            || action.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) return;
        if (!player.isFallFlying() || !aimEnabled() || mc.screen != null) return;
        int age = player.getUseItemRemainingTicks();
        if (age < 3) { reject(event, "bow draw too short to spawn an arrow"); return; }
        if (fullDraw.get() && !automaticReleasing && age < 20) { reject(event, "charge the bow fully"); return; }
        if (tick < nextAutoTick || corrected.get()) { reject(event, "server correction; short pause"); return; }
        if (!validTarget(target)) {
            target = persistentLock() ? null : chooseTarget();
            targetPosition = target == null ? null : observedPosition(target);
            targetVelocity = Vec3.ZERO;
            prediction.resetMotion();
        }
        if (target == null) { reject(event, "no living target in range near the crosshair"); return; }
        Aim aim = aimedShot(age);
        if (aim == null) { reject(event, aimFailure); return; }
        if (!clearMuzzle(aim.yaw(), aim.pitch())) { reject(event, "muzzle blocked"); return; }
        if (crystalGuard.get() && !guard.clear(world, player, player.getDeltaMovement(), crystalsProtected)) {
            reject(event, "crystal or hazard danger");
            return;
        }
        event.cancel();
        Vec3 flight = inheritedFlight();
        record("AIM tick=%d target=%d name=%s yaw=%.2f pitch=%.2f flight=%s lead=%.2f draw=%d",
            tick, target.getId(), target.getName().getString(), aim.yaw(), aim.pitch(), flight, predictedLead, age);
        sendingRelease = true;
        try {
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                player.getX(), player.getY(), player.getZ(), aim.yaw(), aim.pitch(),
                player.onGround(), player.horizontalCollision));
            mc.getConnection().send(action);
        } finally {
            sendingRelease = false;
            releaseTick = tick;
        }
        status = "shot sent";
        if (chatInfo.get()) info("Shot sent with flight %.2f b/t; ideal arrow speed %.2f b/t.",
            flight.length(), BoatShotAim.bowSpeed(bowTicks(age)));
    }

    private void reject(PacketEvent.Send event, String reason) {
        event.cancel();
        cancelDraw();
        status = reason;
        record("REJECT tick=%d reason=%s detail=%s", tick, reason, obstruction);
        if (chatInfo.get()) warning("Shot canceled: %s.", reason);
    }

    @EventHandler private void onReceive(PacketEvent.Receive event) {
        if (connection == null || event.connection != connection || event.isCancelled()) return;
        feedback.offer(event.packet);
        if (event.packet instanceof ClientboundAddEntityPacket spawn && spawn.getId() == playerId
            && (spawn.getType() == EntityType.ARROW || spawn.getType() == EntityType.SPECTRAL_ARROW)) {
            if (spawns.size() >= 32) spawns.poll();
            spawns.offer(spawn);
        }
        if (event.packet instanceof ClientboundPlayerPositionPacket) corrected.set(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (perchVy != 0 && sameSession() && player.isFallFlying() && !player.getAbilities().flying) {
            ((IVec3) event.movement).meteor$set(event.movement.x, perchVy, event.movement.z);
            appliedPerchVy = perchVy;
        } else {
            appliedPerchVy = 0;
        }
    }

    private void observeArrows() {
        ClientboundAddEntityPacket spawn;
        while ((spawn = spawns.poll()) != null) {
            if (seenArrows.put(spawn.getId(), true) != null) continue;
            while (seenArrows.size() > 4096) seenArrows.remove(seenArrows.keySet().iterator().next());
            arrows.put(spawn.getId(), tick + 200);
            while (arrows.size() > 32) arrows.remove(arrows.keySet().iterator().next());
            double speed = spawn.getMovement().length();
            record("ARROW tick=%d id=%d velocity=%s speed=%.3f", tick, spawn.getId(), spawn.getMovement(), speed);
            if (tick - releaseTick <= 20 && speed > 1 && chatInfo.get()) info("Server arrow spawn speed: %.2f b/t.", speed);
        }
        arrows.entrySet().removeIf(entry -> tick > entry.getValue());
    }

    private boolean validTarget(LivingEntity entity) {
        if (entity == null || entity == player || !entity.isAlive() || entity.isRemoved()
            || entity instanceof ArmorStand || world.getEntity(entity.getId()) != entity
            || !persistentLock() && player.distanceTo(entity) > targetRange.get()) return false;
        if (player.getVehicle() != null && entity.getRootVehicle() == player.getRootVehicle()) return false;
        if (entity instanceof Player other) {
            if (other.isSpectator() || other.getAbilities().instabuild || !Friends.get().shouldAttack(other)) return false;
        }
        return true;
    }

    private LivingEntity chooseTarget() {
        if (crosshairSelection.get()) return chooseCrosshairTarget();
        Vec3 eye = player.getEyePosition(), view = Vec3.directionFromRotation(player.getYRot(0), player.getXRot(0));
        LivingEntity best = null;
        double bestScore = -2, minimum = Math.cos(Math.toRadians(targetAngle.get()));
        for (Entity entity : world.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !validTarget(living) || player.distanceTo(living) > targetRange.get()) continue;
            Vec3 offset = living.getBoundingBox().getCenter().subtract(eye);
            double score = view.dot(offset.normalize());
            if (score < minimum) continue;
            boolean betterGroup = preferPlayers.get() && living instanceof Player && !(best instanceof Player);
            boolean sameGroup = !preferPlayers.get() || (living instanceof Player) == (best instanceof Player);
            if (best == null || betterGroup || sameGroup && score > bestScore) { best = living; bestScore = score; }
        }
        if (best != null) {
            record("LOCK tick=%d id=%d name=%s cosine=%.4f", tick, best.getId(), best.getName().getString(), bestScore);
            if (chatInfo.get()) info("Locked: %s.", best.getName().getString());
        }
        return best;
    }

    private LivingEntity chooseCrosshairTarget() {
        var camera = mc.gameRenderer.getMainCamera().entity();
        Vec3 eye = camera.getEyePosition(), view = Vec3.directionFromRotation(camera.getYRot(0), camera.getXRot(0));
        LivingEntity best = null;
        BoatShotCrosshair.Score bestScore = null;
        double minimum = Math.cos(Math.toRadians(crosshairAngle.get()));
        for (Entity entity : world.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !validTarget(living) || player.distanceTo(living) > targetRange.get()) continue;
            AABB body = living.getBoundingBox().move(living.getInterpolation().position().subtract(living.position()));
            var score = BoatShotCrosshair.score(body, eye, view, targetRange.get());
            if (score == null || score.cosine() < minimum || !score.betterThan(bestScore)) continue;
            if (world.clipIncludingBorder(new ClipContext(eye, score.point(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) continue;
            best = living;
            bestScore = score;
        }
        if (best != null) {
            record("LOCK-CROSSHAIR tick=%d id=%d cosine=%.6f distance=%.2f", tick, best.getId(), bestScore.cosine(), bestScore.distance());
            if (chatInfo.get()) info("Locked: %s.", best.getName().getString());
        }
        return best;
    }

    private static double serverTps() { return TickRate.INSTANCE == null ? 20 : TickRate.INSTANCE.getTickRate(); }

    private static Vec3 observedPosition(LivingEntity entity) {
        var interpolation = entity.getInterpolation();
        return interpolation == null ? entity.position() : interpolation.position();
    }

    private AABB observedTargetBox() {
        return target.getBoundingBox().move(targetPosition.subtract(target.position()));
    }

    private boolean drawingBow() {
        return player != null && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
            && player.getActiveItem().getItem() instanceof BowItem;
    }

    private int bowTicks(int clientTicks) {
        return adaptivePrediction.get() || automaticFiring()
            ? Math.max(0, (int) Math.floor(clientTicks * BoatShotPrediction.serverTicksPerClientTick(serverTps()))) : clientTicks;
    }

    private double aimLead() {
        double lead = predictedLead + leadAdjustment.get();
        return adaptivePrediction.get() ? Math.clamp(lead, 0, 10) : BoatShotPrediction.collisionLead(lead);
    }

    private boolean protectCrystals() {
        if (!crystalGuard.get()) return true;
        return drawingBow() || automaticFiring() && validTarget(target)
            && player.getMainHandItem().getItem() instanceof BowItem;
    }

    private void refreshGuard() {
        guard.refresh(world, player, player, target, crystalsProtected);
        lastGuardRefresh = tick;
        guardTarget = target;
        guardProtect = crystalsProtected;
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (showTarget.get() && sameSession() && validTarget(target)) {
            Color line = targetColor.get();
            AABB box = target.getBoundingBox().inflate(.08);
            event.renderer.box(box, new Color(line.r, line.g, line.b, targetFill.get()), line, ShapeMode.Both, 0);
            event.renderer.box(box.inflate(.045), new Color(0, 0, 0, 0), new Color(255, 255, 255, 220), ShapeMode.Lines, 0);
            event.renderer.box(new AABB(box.minX - .12, box.maxY + .15, box.minZ - .12, box.maxX + .12, box.maxY + .23, box.maxZ + .12),
                new Color(line.r, line.g, line.b, 180), line, ShapeMode.Both, 0);
        }
    }

    @EventHandler private void onRenderStatus(Render2DEvent event) {
        if (!combatStatus.get() || !sameSession() || mc.options.hideGui) return;
        TextRenderer text = TextRenderer.get();
        if (text.isBuilding()) return;
        String label = "ElytraShot | " + status;
        text.begin(1.2);
        double width = text.getWidth(label, true), height = text.getHeight();
        double x = Math.max(4, (event.screenWidth - width) / 2), y = Math.max(4, event.screenHeight - 100 - height);
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 8, y - 5, width + 16, height + 10, new Color(10, 14, 20, 215));
        Renderer2D.COLOR.render();
        text.render(label, x, y, new Color(255, 220, 110), true);
        text.end();
    }

    private void unlockTarget() {
        if (!isActive()) return;
        stopAutomaticDraw();
        abort("target unlocked", true);
        suppressRelease = true;
        try {
            if (sameSession() && drawingBow()) { cancelDraw(); player.releaseUsingItem(); }
        } finally { suppressRelease = false; }
        clearTarget();
        suppressAcquire = true;
        info("Target unlocked; automatic firing stopped.");
    }

    private void toggleTargetLock() {
        if (!isActive() || !sameSession()) return;
        if (target != null) { unlockTarget(); return; }
        if (mc.screen != null) return;
        target = chooseTarget();
        targetPosition = target == null ? null : observedPosition(target);
        targetVelocity = Vec3.ZERO;
        prediction.resetMotion();
        acquiredTarget();
    }

    private boolean sameSession() {
        return player != null && player == mc.player && world == mc.level && network == mc.getConnection()
            && network.getConnection() == connection;
    }

    private void stopAutomaticDraw() {
        if (ownedAutoDraw && sameSession() && drawingBow()) {
            suppressRelease = true;
            try { cancelDraw(); player.releaseUsingItem(); }
            finally { suppressRelease = false; }
        }
        ownedAutoDraw = false;
    }

    private void cancelDraw() {
        if (!sameSession()) return;
        int selected = player.getInventory().getSelectedSlot();
        sendingRelease = true;
        try {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket((selected + 1) % 9));
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(selected));
        } finally { sendingRelease = false; }
    }

    private void abort(String reason, boolean release) {
        if (release) stopAutomaticDraw();
        status = reason;
    }

    private void resetSession() {
        connection = null;
        player = null;
        world = null;
        network = null;
        corrected.set(false);
        sendingRelease = false;
        suppressRelease = false;
        automaticReleasing = false;
        ownedAutoDraw = false;
        suppressAcquire = false;
        spawns.clear();
        arrows.clear();
        seenArrows.clear();
        feedback.reset();
        guard.reset();
        prediction.reset();
        predictedLead = 2;
        crystalsProtected = false;
        guardProtect = false;
        lastGuardRefresh = -100;
        guardTarget = null;
        perchVy = 0;
        appliedPerchVy = 0;
        resetPerchRamp();
        aimCacheValid = false;
        aimCacheTick = -100;
        logDirty = false;
        clearTarget();
        target = null;
        playerId = -1;
        tick = 0;
        releaseTick = -100;
        nextAutoTick = 0;
        lastWaitLog = -100;
        lastFlow = "";
        status = "waiting for session";
        aimFailure = "";
        obstruction = "";
        if (log != null) { log.close(); log = null; }
    }

    private void pauseConflicts() {
        for (String name : CONFLICTS) {
            Module module = Modules.get().get(name);
            if (module != null && module.isActive()) {
                module.toggle();
                if (!paused.contains(module)) paused.add(module);
            }
        }
    }

    private void restoreConflicts() {
        for (Module module : paused) if (!module.isActive()) module.toggle();
        paused.clear();
    }

    private void openLog() {
        if (!debugFile.get()) return;
        try {
            Path directory = mc.gameDirectory.toPath().resolve("elytra-shot");
            Files.createDirectories(directory);
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            log = new PrintWriter(Files.newBufferedWriter(directory.resolve("shot-" + time + ".log"), StandardCharsets.UTF_8));
            record("START ElytraShot v1; flight-velocity bow fire while gliding");
            for (SettingGroup group : settings) for (Setting<?> setting : group) record("cfg %s = %s", setting.name, setting.get());
            flushLog();
        } catch (IOException e) { warning("Could not open Elytra Shot log: %s", e.getMessage()); }
    }

    private void record(String format, Object... args) {
        if (log != null) { log.printf(Locale.ROOT, format + "%n", args); logDirty = true; }
    }

    private void flushLog() {
        if (logDirty && log != null) { log.flush(); logDirty = false; }
    }

    @Override public String getInfoString() {
        return status;
    }
}
