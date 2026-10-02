package com.chestautosorter.diagnostic;

import com.chestautosorter.server.ChestSnapshot;
import com.chestautosorter.server.SafeCommitter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes a human-readable recovery record BEFORE any inventory write. The encoding uses
 * {@link ItemStack#CODEC} so full component data and large counts round-trip.
 *
 * This is a recovery aid, not a power-loss atomic transaction: it is never replayed automatically.
 */
public final class RecoveryLog {

    private static final int MAX_WRITES = 200;

    private RecoveryLog() {}

    /**
     * Writes the pre-images of the target chests before any inventory write.
     *
     * @return the written file, or {@code null} if the record could not be persisted completely.
     *         Callers must treat {@code null} as a hard failure and abort the sort: a partial or
     *         unencodable record is worse than none.
     */
    public static Path write(UUID requestId, ServerLevel level, List<SafeCommitter.BlockPosKey> keys,
                             List<ChestSnapshot> snaps) {
        try {
            Path dir = Path.of("chestautosorter-recovery");
            Files.createDirectories(dir);
            Path file = dir.resolve("recovery-" + Instant.now().toString().replace(':', '-') + ".snbt");

            StringBuilder sb = new StringBuilder();
            sb.append("operation=").append(requestId).append('\n');
            sb.append("dimension=").append(level.dimension().identifier()).append('\n');
            sb.append("time=").append(Instant.now()).append('\n');
            sb.append("containers=").append(snaps.size()).append('\n');

            for (int ci = 0; ci < snaps.size(); ci++) {
                SafeCommitter.BlockPosKey k = keys.get(ci);
                ChestSnapshot snap = snaps.get(ci);
                sb.append("chest ").append(k.x()).append(',').append(k.y()).append(',').append(k.z())
                        .append(" size=").append(snap.size).append(" double=").append(snap.doubleChest).append('\n');
                for (int i = 0; i < snap.size; i++) {
                    ItemStack s = snap.slots.get(i);
                    if (s.isEmpty()) {
                        continue;
                    }
                    var encoded = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, s).result();
                    if (encoded.isEmpty()) {
                        // Never write a placeholder: an unreadable record would silently lose items.
                        Diag.error("recovery log encode failed for slot " + i + " of chest "
                                + k.x() + "," + k.y() + "," + k.z() + "; aborting record");
                        return null;
                    }
                    sb.append("  slot ").append(i).append(" = ").append(encoded.get().toString()).append('\n');
                }
            }

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Diag.phase(requestId, "recovery_written", file.toAbsolutePath().toString());
            prune(dir);
            return file;
        } catch (IOException | RuntimeException e) {
            Diag.error("recovery log write failed: " + e);
            return null;
        }
    }

    private static void prune(Path dir) {
        try (var stream = Files.list(dir)) {
            var files = stream.filter(p -> p.getFileName().toString().endsWith(".snbt"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .toList();
            for (int i = MAX_WRITES; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    // --- read-back (used by verification tests and by the on-disk review path) ----------------

    /** One recorded slot: which chest, which slot, and the decoded stack. */
    public record RecordedSlot(int chestX, int chestY, int chestZ, int slot, ItemStack stack) {}

    /**
     * Parses a record written by {@link #write}. Slot lines carry a full {@code ItemStack} encoded with
     * {@link ItemStack#CODEC}; a line that fails to decode aborts the read rather than being skipped,
     * so a corrupted record can never be mistaken for a complete one.
     */
    public static List<RecordedSlot> read(Path file) {
        List<RecordedSlot> out = new ArrayList<>();
        try {
            int cx = 0;
            int cy = 0;
            int cz = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("chest ")) {
                    String[] parts = trimmed.substring("chest ".length()).split(" ", 2)[0].split(",");
                    cx = Integer.parseInt(parts[0]);
                    cy = Integer.parseInt(parts[1]);
                    cz = Integer.parseInt(parts[2]);
                } else if (trimmed.startsWith("slot ")) {
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    int slot = Integer.parseInt(trimmed.substring("slot ".length(), eq).trim());
                    String encoded = trimmed.substring(eq + 1).trim();
                    Tag tag;
                    try {
                        tag = TagParser.parseCompoundFully(encoded);
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                        throw new IllegalStateException("undecodable slot " + slot, e);
                    }
                    ItemStack stack = ItemStack.CODEC.parse(NbtOps.INSTANCE, tag)
                            .result()
                            .orElseThrow(() -> new IllegalStateException("undecodable slot " + slot));
                    out.add(new RecordedSlot(cx, cy, cz, slot, stack));
                }
            }
        } catch (IOException | RuntimeException e) {
            Diag.error("recovery read failed for " + file + ": " + e);
            throw new IllegalStateException("recovery record unreadable: " + file, e);
        }
        return out;
    }
}
