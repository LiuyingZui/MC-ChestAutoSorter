package com.chestautosorter.server;

import com.chestautosorter.config.CasConfig;
import com.chestautosorter.core.Category;
import com.chestautosorter.core.SortPlanner;
import com.chestautosorter.core.StackEntry;
import com.chestautosorter.diagnostic.Diag;
import com.chestautosorter.diagnostic.RecoveryLog;
import com.chestautosorter.network.Msg;
import com.chestautosorter.network.SortRequestPayload;
import com.chestautosorter.network.SortResultPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Server-side request handling. Inventory authority lives here, never on the client. */
public final class SortService {

    public static final int RESULT_OK = 0;
    public static final int RESULT_EMPTY = 1;
    public static final int RESULT_REJECTED = 2;
    public static final int RESULT_PREVIEW = 3;
    public static final int RESULT_ERROR = 9;

    private static long lastAcceptedMs = 0L;

    private SortService() {}

    public static void onRequest(SortRequestPayload payload, IPayloadContext context) {
        long t0 = System.nanoTime();
        ServerPlayer player = (ServerPlayer) context.player();

        if (player == null) {
            Diag.warn("request dropped: no server player");
            return;
        }

        int radius = clampRadius(payload.radius(), CasConfig.server().radius.get());
        List<BlockPos> candidates = payload.candidates();

        if (candidates.isEmpty()) {
            reply(context, payload, RESULT_EMPTY, List.of(Msg.key("chestautosorter.result.empty_view")), List.of(), t0, 0, 0, 0, 0);
            return;
        }
        if (candidates.size() > SortRequestPayload.MAX_CANDIDATES) {
            reply(context, payload, RESULT_REJECTED, List.of(Msg.key("chestautosorter.result.too_many")), List.of(), t0, 0, 0, 0, 0);
            return;
        }

        // --- 1. Resolve + validate (server authority) ---
        ServerLevel level = player.level();
        List<String> skipped = new ArrayList<>();
        Map<BlockPos, Container> resolved = validate(player, candidates, radius, skipped);

        long scanMicros = (System.nanoTime() - t0) / 1000;

        if (resolved.isEmpty()) {
            reply(context, payload, RESULT_EMPTY, List.of(Msg.key("chestautosorter.result.no_chest")), skipped, t0, 0, 0, 0, 0);
            return;
        }
        if (resolved.size() > CasConfig.server().maxContainers.get()) {
            reply(context, payload, RESULT_REJECTED,
                    List.of(Msg.key("chestautosorter.result.max_containers", CasConfig.server().maxContainers.get())), skipped, t0, 0, 0, 0, 0);
            return;
        }

        // --- 2. Cooldown ---
        // Only commits consume the cooldown window. A preview followed by its confirm would
        // otherwise always be rejected, because the two clicks land inside one window.
        long now = System.currentTimeMillis();
        if (payload.preview()) {
            if (now - lastAcceptedMs < Math.max(250L, CasConfig.server().cooldownMs.get())) {
                Diag.debug("preview throttled by cooldown");
                reply(context, payload, RESULT_REJECTED, List.of(Msg.key("chestautosorter.result.cooldown")), skipped, t0, 0, 0, 0, 0);
                return;
            }
        } else {
            if (now - lastAcceptedMs < Math.max(250L, CasConfig.server().cooldownMs.get())) {
                reply(context, payload, RESULT_REJECTED, List.of(Msg.key("chestautosorter.result.cooldown")), skipped, t0, 0, 0, 0, 0);
                return;
            }
            lastAcceptedMs = now;
        }

        var server = level.getServer();
        if (server != null && server.isDedicatedServer()) {
            reply(context, payload, RESULT_REJECTED, List.of(Msg.key("chestautosorter.result.dedicated_server")), skipped, t0, 0, 0, 0, 0);
            return;
        }

        // --- 3. Snapshot (independent copies; canonical order) ---
        List<BlockPos> ordered = SupportedChests.canonicalOrder(new ArrayList<>(resolved.keySet()));
        List<Container> containers = new ArrayList<>();
        List<ChestSnapshot> snaps = new ArrayList<>();
        List<SafeCommitter.BlockPosKey> keys = new ArrayList<>();
        for (BlockPos pos : ordered) {
            Container c = resolved.get(pos);
            containers.add(c);
            boolean dbl = SupportedChests.chestType(level, pos) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE;
            snaps.add(ChestSnapshot.of(pos, c, dbl));
            keys.add(new SafeCommitter.BlockPosKey(pos.getX(), pos.getY(), pos.getZ()));
        }

        // --- 4. Classify + aggregate + plan (shared orchestration; no writes) ---
        long planStart = System.nanoTime();
        boolean allowMixed = CasConfig.server().allowMixedBoxes.get();
        SortPipelines.Plan plan = SortPipelines.plan(containers, snaps, allowMixed);
        SortPlanner.Result planned = plan.planner;
        List<StackEntry> aggregated = plan.aggregated;
        long planMicros = (System.nanoTime() - planStart) / 1000;

        if (Diag.isDebug()) {
            for (StackEntry e : aggregated) {
                Diag.debug("agg " + e.key().itemId() + " x" + e.count() + " max=" + e.maxStack() + " -> " + e.category().zh());
            }
        }

        // The layout mode is the one number a player cannot see from the result alone, and it is what
        // explains why items landed where they did.
        Diag.info("req=" + payload.requestId() + " plan mode=" + planned.status + " chests=" + plan.sizes.size()
                + " categories=" + aggregated.stream().map(StackEntry::category).distinct().count()
                + " stacks=" + aggregated.size());

        // --- 5. Preview (no writes) ---
        if (payload.preview()) {
            List<String> lines = new ArrayList<>();
            lines.add(Msg.key("chestautosorter.result.preview_summary", snaps.size(), plan.sizes.stream().mapToInt(Integer::intValue).sum(), aggregated.size()));
            if (planned.isCancelled()) {
                lines.addAll(planned.messages);
            } else {
                lines.add(layoutMessage(planned));
                lines.addAll(categorySummary(planned));
            }
            reply(context, payload, RESULT_PREVIEW, lines, skipped, t0, planMicros, 0, snaps.size(), aggregated.size());
            return;
        }

        // --- 6. Commit (shared orchestration) ---
        if (planned.isCancelled()) {
            reply(context, payload, RESULT_REJECTED, planned.messages, skipped, t0, planMicros, 0, snaps.size(), aggregated.size());
            return;
        }

        long commitStart = System.nanoTime();
        // The pre-image must be persisted before the first write; refuse to sort otherwise.
        Path recovery = RecoveryLog.write(payload.requestId(), level, keys, snaps);
        if (recovery == null) {
            Diag.error("req=" + payload.requestId() + " recovery_write_failed; sort aborted before any write");
            reply(context, payload, RESULT_ERROR,
                    List.of(Msg.key("chestautosorter.result.recovery_failed")),
                    skipped, t0, planMicros, 0, snaps.size(), aggregated.size());
            return;
        }
        SafeCommitter.Outcome outcome = SortPipelines.commit(plan, keys);
        long commitMicros = (System.nanoTime() - commitStart) / 1000;

        List<String> lines = new ArrayList<>();
        if (outcome.success) {
            lines.add(Msg.key("chestautosorter.result.done", snaps.size(), aggregated.size()));
            lines.add(doneMessage(planned.status));
            lines.addAll(categorySummary(planned));
            lines.add(Msg.key("chestautosorter.result.timing", scanMicros / 1000, planMicros / 1000, commitMicros / 1000));
            Diag.phase(payload.requestId(), "commit_ok", "chests=" + snaps.size() + " stacks=" + aggregated.size()
                    + " scanUs=" + scanMicros + " planUs=" + planMicros + " commitUs=" + commitMicros);
            reply(context, payload, RESULT_OK, lines, skipped, t0, planMicros, commitMicros, snaps.size(), aggregated.size());
        } else {
            lines.add(outcome.message);
            if (outcome.rolledBack) {
                lines.add(Msg.key(outcome.rollbackVerified ? "chestautosorter.result.rolled_back_ok" : "chestautosorter.result.rolled_back_failed"));
            }
            Diag.error("req=" + payload.requestId() + " commit_failed " + outcome.message
                    + " rolledBack=" + outcome.rolledBack + " rollbackVerified=" + outcome.rollbackVerified);
            reply(context, payload, RESULT_ERROR, lines, skipped, t0, planMicros, commitMicros, snaps.size(), aggregated.size());
        }
    }

    /** Which layout line the preview shows; the spread wording carries what actually happened. */
    private static String layoutMessage(SortPlanner.Result plan) {
        if (plan.status == SortPlanner.Status.OK_DEDICATED) {
            return Msg.key("chestautosorter.result.layout_dedicated");
        }
        if (plan.status == SortPlanner.Status.OK_SPREAD) {
            int[] spread = chestSpread(plan.layout);
            return Msg.key("chestautosorter.result.layout_spread", spread[0], spread[1]);
        }
        return Msg.key("chestautosorter.result.layout_mixed");
    }

    private static String doneMessage(SortPlanner.Status status) {
        if (status == SortPlanner.Status.OK_DEDICATED) {
            return Msg.key("chestautosorter.result.done_dedicated");
        }
        if (status == SortPlanner.Status.OK_SPREAD) {
            return Msg.key("chestautosorter.result.done_spread");
        }
        return Msg.key("chestautosorter.result.done_mixed");
    }

    /** @return {chests holding items, chests that ended up holding more than one category} */
    private static int[] chestSpread(List<SortPlanner.SlotPlan> layout) {
        Map<Integer, LinkedHashSet<Category>> perChest = new LinkedHashMap<>();
        for (SortPlanner.SlotPlan p : layout) {
            if (!p.isEmpty()) {
                perChest.computeIfAbsent(p.container, k -> new LinkedHashSet<>()).add(p.entry.category());
            }
        }
        int shared = 0;
        for (LinkedHashSet<Category> cats : perChest.values()) {
            if (cats.size() > 1) {
                shared++;
            }
        }
        return new int[]{perChest.size(), shared};
    }

    private static List<String> categorySummary(SortPlanner.Result plan) {
        Map<Category, Long> perCat = new LinkedHashMap<>();
        for (SortPlanner.SlotPlan p : plan.layout) {
            if (!p.isEmpty()) {
                perCat.merge(p.entry.category(), p.entry.count(), Long::sum);
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Category, Long> e : perCat.entrySet()) {
            lines.add(Msg.key("chestautosorter.category." + e.getKey().name().toLowerCase(java.util.Locale.ROOT),
                    e.getValue()));
        }
        if (plan.status == SortPlanner.Status.OK_MIXED) {
            lines.add(Msg.key("chestautosorter.result.mixed_categories", perCat.size()));
        }
        return lines;
    }

    /** Client-side handler for the reply. Must never be loaded on a dedicated server. */
    public static void onResultClient(SortResultPayload payload, IPayloadContext context) {
        if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) {
            com.chestautosorter.client.CasClientResult.handle(payload);
        }
    }

    private static void reply(IPayloadContext context, SortRequestPayload req, int code, List<String> lines,
                              List<String> skipped, long t0, long planMicros, long commitMicros,
                              int chests, int stacks) {
        long scanMicros = (System.nanoTime() - t0) / 1000;
        Diag.phase(req.requestId(), "server_result",
                "code=" + code + " chests=" + chests + " stacks=" + stacks + " skipped=" + skipped.size());
        context.reply(new SortResultPayload(req.requestId(), code, lines, skipped, scanMicros, planMicros, commitMicros, chests, stacks));
    }

    private static int clampRadius(int requested, int configured) {
        int r = requested < 1 ? configured : Math.min(requested, 64);
        return Math.max(1, r);
    }

    private static String containerIdentity(Container container) {
        return System.identityHashCode(container) + ":" + container.getContainerSize();
    }

    /**
     * Server-side validation of the client's candidate list. The client's ticks are never an
     * authorisation: every entry is re-resolved and re-checked against the live world here.
     *
     * <p>Range is the mod's own rule — eyes to the logical container bounds — so a double chest
     * gives the same verdict whichever half was picked, and vanilla's interaction range (4.5 + 4.0
     * buffer, measured to a single block) does not apply. Returns the accepted containers in
     * encounter order; rejected entries are appended to {@code skipped} with a specific reason.
     */
    public static Map<BlockPos, Container> validate(ServerPlayer player, List<BlockPos> candidates,
                                                    int radius, List<String> skipped) {
        Map<BlockPos, Container> resolved = new LinkedHashMap<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ServerLevel level = player.level();

        for (BlockPos raw : candidates) {
            BlockPos pos = raw.immutable();
            if (SupportedChests.eyeDistanceTo(level, pos, player) > radius) {
                skipped.add(fmt(pos, Msg.key("chestautosorter.reason.too_far")));
                continue;
            }
            if (!level.isLoaded(pos)) {
                skipped.add(fmt(pos, Msg.key("chestautosorter.reason.chunk_unloaded")));
                continue;
            }
            if (SupportedChests.kindOf(level, pos) == SupportedChests.Kind.UNSUPPORTED) {
                skipped.add(fmt(pos, Msg.key("chestautosorter.reason.unsupported",
                        level.getBlockState(pos).getBlock().getName().getString())));
                continue;
            }
            Container container = SupportedChests.resolve(level, pos);
            if (container == null) {
                skipped.add(fmt(pos, Msg.key("chestautosorter.reason.unresolvable")));
                continue;
            }
            if (!seen.add(containerIdentity(container))) {
                skipped.add(fmt(pos, Msg.key("chestautosorter.reason.duplicate")));
                continue;
            }
            BlockPos other = SupportedChests.otherHalf(level, pos);
            if (other != null && !SupportedChests.isChestEntity(level, other)) {
                skipped.add(fmt(pos, Msg.key("chestautosorter.reason.broken_double")));
                continue;
            }
            BlockPos anchor = SupportedChests.canonicalAnchor(level, pos);
            if (ChestBlockEntity.getOpenCount(level, anchor) > 0) {
                skipped.add(fmt(pos, Msg.key("chestautosorter.reason.in_use")));
                continue;
            }
            if (CasConfig.server().protectCustomNamed.get()) {
                var be = level.getBlockEntity(pos);
                if (be instanceof net.minecraft.world.Nameable nameable && nameable.hasCustomName()) {
                    skipped.add(fmt(pos, Msg.key("chestautosorter.reason.protected")));
                    continue;
                }
            }
            resolved.put(pos, container);
        }
        return resolved;
    }

    private static String fmt(BlockPos pos, String reasonWire) {
        // The position is plain text; the reason is a translation key that the client renders in the
        // receiving player's language. Msg.SEP keeps the two parts separable on that side.
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")" + Msg.SEP + reasonWire;
    }
}
