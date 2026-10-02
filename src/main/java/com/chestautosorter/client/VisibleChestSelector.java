package com.chestautosorter.client;

import com.chestautosorter.diagnostic.Diag;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative "in view" selection: supported chests within radius AND intersecting the camera
 * frustum AND with at least one sample point on the chest surface reachable by a non-blocking ray
 * from the camera. Large (double) chests are treated as one logical container.
 *
 * The frustum is built from FOV/aspect and the camera rotation directly (no renderer injection),
 * so it stays valid even with Sodium/Iris installed.
 */
public final class VisibleChestSelector {

    private VisibleChestSelector() {}

    public static final class Result {
        public final List<BlockPos> candidates;
        public final int scanned;
        public final int tooFar;
        public final int offscreen;
        public final int occluded;
        public final boolean firstPerson;

        Result(List<BlockPos> candidates, int scanned, int tooFar, int offscreen, int occluded, boolean firstPerson) {
            this.candidates = candidates;
            this.scanned = scanned;
            this.tooFar = tooFar;
            this.offscreen = offscreen;
            this.occluded = occluded;
            this.firstPerson = firstPerson;
        }
    }

    public static Result select(int radius) {
        Minecraft mc = Minecraft.getInstance();
        List<BlockPos> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) {
            return new Result(out, 0, 0, 0, 0, true);
        }

        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Frustum frustum = buildFrustum(mc, camera);

        Level level = mc.level;
        BlockPos center = mc.player.blockPosition();
        int r = radius;

        int scanned = 0;
        int tooFar = 0;
        int offscreen = 0;
        int occluded = 0;

        Set<BlockPos> handled = new LinkedHashSet<>();

        int chunkRadius = (r >> 4) + 2;
        int ccx = center.getX() >> 4;
        int ccz = center.getZ() >> 4;

        for (int cx = ccx - chunkRadius; cx <= ccx + chunkRadius; cx++) {
            for (int cz = ccz - chunkRadius; cz <= ccz + chunkRadius; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                for (var be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (handled.contains(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof ChestBlock)) {
                        continue;
                    }
                    scanned++;

                    BlockPos key = normaliseKey(pos, state);
                    if (key != pos && handled.contains(key)) {
                        handled.add(pos);
                        scanned--;
                        continue;
                    }
                    handled.add(key);
                    handled.add(pos);
                    if (state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
                        handled.add(pos.relative(ChestBlock.getConnectedDirection(state)));
                    }

                    AABB box = wholeBox(level, pos, state);
                    double dist = distanceToBox(eye, box);
                    if (dist > r) {
                        tooFar++;
                        continue;
                    }
                    if (frustum != null && !frustum.isVisible(box)) {
                        offscreen++;
                        continue;
                    }
                    if (!hasVisibleSample(level, mc, eye, box, camera)) {
                        occluded++;
                        continue;
                    }
                    out.add(pos);
                }
            }
        }

        out.sort(Comparator.comparingInt((BlockPos p) -> p.getY())
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getX));

        int limit = 64;
        if (out.size() > limit) {
            out = new ArrayList<>(out.subList(0, limit));
        }

        Diag.debug("selector: scanned=" + scanned + " tooFar=" + tooFar + " offscreen=" + offscreen
                + " occluded=" + occluded + " selected=" + out.size() + " firstPerson=" + firstPerson);

        return new Result(out, scanned, tooFar, offscreen, occluded, firstPerson);
    }

    /** Deterministic (y, z, x) order so a double chest maps to a stable representative. */
    private static BlockPos normaliseKey(BlockPos pos, BlockState state) {
        ChestType type = state.getValue(BlockStateProperties.CHEST_TYPE);
        if (type == ChestType.SINGLE) {
            return pos;
        }
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
        return compare(pos, other) <= 0 ? pos : other;
    }

    private static int compare(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        if (a.getZ() != b.getZ()) {
            return Integer.compare(a.getZ(), b.getZ());
        }
        return Integer.compare(a.getX(), b.getX());
    }

    private static AABB wholeBox(Level level, BlockPos pos, BlockState state) {
        AABB b = new AABB(pos).inflate(0.02);
        ChestType type = state.getValue(BlockStateProperties.CHEST_TYPE);
        if (type != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (level.hasChunkAt(other) && level.getBlockState(other).getBlock() instanceof ChestBlock) {
                b = b.minmax(new AABB(other).inflate(0.02));
            }
        }
        return b;
    }

    private static double distanceToBox(Vec3 p, AABB box) {
        double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean hasVisibleSample(Level level, Minecraft mc, Vec3 eye, AABB box, Camera camera) {
        Vec3 look = new Vec3(camera.forwardVector()).normalize();
        Vec3 c = box.getCenter();
        List<Vec3> samples = new ArrayList<>(6);
        samples.add(c);
        samples.add(new Vec3(c.x, c.y, box.minZ));
        samples.add(new Vec3(c.x, c.y, box.maxZ));
        samples.add(new Vec3(box.minX, c.y, c.z));
        samples.add(new Vec3(box.maxX, c.y, c.z));
        // Try the face most directly in front of the camera first.
        samples.sort(Comparator.comparingDouble((Vec3 v) -> -v.subtract(eye).normalize().dot(look)));
        for (Vec3 sample : samples) {
            BlockHitResult hit = level.clip(new ClipContext(eye, sample, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private static Frustum buildFrustum(Minecraft mc, Camera camera) {
        try {
            var window = mc.getWindow();
            float aspect = (float) window.getWidth() / (float) Math.max(1, window.getHeight());
            float fovDeg = camera.getFov();
            Matrix4f projection = new Matrix4f().perspective(
                    (float) Math.toRadians(fovDeg), aspect, 0.05F, (float) (mc.options.renderDistance().get() * 16 + 64));
            Matrix4f modelView = new Matrix4f().rotation(new Quaternionf(camera.rotation()).conjugate());
            Vec3 eye = camera.position();
            modelView.translate((float) -eye.x, (float) -eye.y, (float) -eye.z);
            return new Frustum(modelView, projection);
        } catch (Throwable t) {
            Diag.debug("frustum build failed: " + t);
            return null;
        }
    }
}
