package com.chestautosorter.diagnostic;

import com.chestautosorter.CAS;
import com.chestautosorter.config.CasConfig;
import com.chestautosorter.server.ChestSnapshot;
import com.chestautosorter.server.SupportedChests;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = CAS.MOD_ID)
public final class CasCommands {

    private CasCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("chestautosorter")
                        .then(Commands.literal("status").executes(ctx -> {
                            var s = CasConfig.server();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "[ChestAutoSorter] radius=" + s.radius.get()
                                            + " maxContainers=" + s.maxContainers.get()
                                            + " cooldownMs=" + s.cooldownMs.get()
                                            + " protectNamed=" + s.protectCustomNamed.get()
                                            + " allowMixed=" + s.allowMixedBoxes.get()
                                            + " debug=" + s.debug.get()), false);
                            return 1;
                        }))
                        .then(Commands.literal("debug")
                                .then(Commands.literal("on").executes(ctx -> {
                                    Diag.setDebug(true);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[ChestAutoSorter] debug 已开启"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    Diag.setDebug(false);
                                    ctx.getSource().sendSuccess(() -> Component.literal("[ChestAutoSorter] debug 已关闭"), false);
                                    return 1;
                                })))
                        .then(Commands.literal("dump").executes(ctx -> {
                            String[] lines = Diag.recent();
                            if (lines.length == 0) {
                                ctx.getSource().sendSuccess(() -> Component.literal("[ChestAutoSorter] 暂无诊断记录"), false);
                                return 0;
                            }
                            for (String line : lines) {
                                ctx.getSource().sendSuccess(() -> Component.literal("[diag] " + line), false);
                            }
                            return lines.length;
                        }))
                        // Reads chests back from the live world and logs every slot, so an acceptance
                        // run can be diffed before/after without relying on the sort code path.
                        .then(Commands.literal("snapshot")
                                .executes(ctx -> {
                                    BlockPos origin = BlockPos.containing(ctx.getSource().getPosition());
                                    int radius = CasConfig.server().radius.get();
                                    List<BlockPos> found = new ArrayList<>();
                                    for (BlockPos p : BlockPos.betweenClosed(
                                            origin.offset(-radius, -radius, -radius),
                                            origin.offset(radius, radius, radius))) {
                                        if (SupportedChests.isSupportedBlock(ctx.getSource().getLevel(), p)) {
                                            found.add(p.immutable());
                                        }
                                    }
                                    List<BlockPos> canonical = SupportedChests.canonicalOrder(found);
                                    Diag.info("snapshot_begin origin=" + origin.toShortString()
                                            + " radius=" + radius + " containers=" + canonical.size());
                                    for (BlockPos p : canonical) {
                                        Container c = SupportedChests.resolve(ctx.getSource().getLevel(), p);
                                        if (c == null) {
                                            continue;
                                        }
                                        boolean dbl = SupportedChests.chestType(ctx.getSource().getLevel(), p)
                                                != net.minecraft.world.level.block.state.properties.ChestType.SINGLE;
                                        ChestSnapshot snap = ChestSnapshot.of(p, c, dbl);
                                        for (int i = 0; i < snap.slots.size(); i++) {
                                            ItemStack s = snap.slots.get(i);
                                            if (s.isEmpty()) {
                                                continue;
                                            }
                                            Diag.info("slot chest=" + p.toShortString() + " i=" + i
                                                    + " item=" + ChestSnapshot.itemId(s)
                                                    + " n=" + s.getCount()
                                                    + " max=" + c.getMaxStackSize(s)
                                                    + " comp=" + s.getComponents());
                                        }
                                    }
                                    Diag.info("snapshot_end containers=" + canonical.size());
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "[ChestAutoSorter] 已写出 " + canonical.size() + " 个容器的逐槽快照到日志。"), false);
                                    return canonical.size();
                                }))
        );
    }
}
