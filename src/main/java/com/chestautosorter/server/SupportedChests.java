package com.chestautosorter.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side authority on which blocks may participate. Only vanilla chests and trapped chests
 * (including their double-chest form) are accepted. Anything else is explicitly skipped.
 */
public final class SupportedChests {

    public enum Kind {
        CHEST, TRAPPED, UNSUPPORTED
    }

    private SupportedChests() {}

    public static Kind kindOf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        var block = state.getBlock();
        if (block == net.minecraft.world.level.block.Blocks.CHEST) {
            return Kind.CHEST;
        }
        if (block == net.minecraft.world.level.block.Blocks.TRAPPED_CHEST) {
            return Kind.TRAPPED;
        }
        return Kind.UNSUPPORTED;
    }

    public static boolean isSupportedBlock(Level level, BlockPos pos) {
        return kindOf(level, pos) != Kind.UNSUPPORTED;
    }

    /** Resolves a chest (possibly one half of a double chest) to its combined Container. */
    public static Container resolve(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock chest)) {
            return null;
        }
        return ChestBlock.getContainer(chest, state, level, pos, true);
    }

    public static ChestType chestType(Level level, BlockPos pos) {
        return level.getBlockState(pos).getValue(BlockStateProperties.CHEST_TYPE);
    }

    public static BlockPos otherHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE) {
            return null;
        }
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    public static BlockEntity blockEntity(Level level, BlockPos pos) {
        return level.getBlockEntity(pos);
    }

    public static boolean isChestEntity(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChestBlockEntity;
    }

    /**
     * A stable anchor for the logical container: the left half for a double chest, so either half
     * resolves to the same block and per-container state (such as the opener count) is not read
     * twice or missed.
     */
    public static BlockPos canonicalAnchor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.RIGHT) {
            return pos;
        }
        BlockPos left = pos.relative(ChestBlock.getConnectedDirection(state));
        return left == null ? pos : left;
    }

    /**
     * The logical container's bounding box: both halves for a double chest, the single block
     * otherwise. Used for the mod's own range rule so picking either half gives the same answer.
     */
    public static AABB bounds(Level level, BlockPos pos) {
        AABB box = new AABB(pos);
        BlockPos other = otherHalf(level, pos);
        return other == null ? box : box.minmax(new AABB(other));
    }

    /**
     * Distance from the player's eyes to the nearest point of the logical container, in blocks.
     *
     * <p>This is the mod's own range rule and deliberately independent of the vanilla menu
     * validity check ({@code Container.stillValidBlockEntity}, whose interaction range is 4.5 plus
     * a 4.0 buffer and which measures to a single block, not the whole double chest).
     */
    public static double eyeDistanceTo(Level level, BlockPos pos, Player player) {
        return Math.sqrt(bounds(level, pos).distanceToSqr(player.getEyePosition()));
    }

    /** Ordered by (y, z, x) for a stable, player-rotation-independent chest ordering. */
    public static List<BlockPos> canonicalOrder(List<BlockPos> positions) {
        List<BlockPos> copy = new ArrayList<>(positions);
        copy.sort((a, b) -> {
            if (a.getY() != b.getY()) {
                return Integer.compare(a.getY(), b.getY());
            }
            if (a.getZ() != b.getZ()) {
                return Integer.compare(a.getZ(), b.getZ());
            }
            return Integer.compare(a.getX(), b.getX());
        });
        return copy;
    }
}
