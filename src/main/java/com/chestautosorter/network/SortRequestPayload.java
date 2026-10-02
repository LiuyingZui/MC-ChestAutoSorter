package com.chestautosorter.network;

import com.chestautosorter.CAS;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client -> server request. Carries only the candidate chest positions the client believes are
 * on-screen; the server re-validates everything and never trusts a client-provided inventory.
 */
public record SortRequestPayload(UUID requestId, long clientTick, boolean preview, List<BlockPos> candidates, int radius)
        implements CustomPacketPayload {

    public static final int MAX_CANDIDATES = 64;

    public static final Type<SortRequestPayload> TYPE = new Type<>(CAS.id("sort_request"));

    public static final StreamCodec<FriendlyByteBuf, SortRequestPayload> CODEC = StreamCodec.of(
            SortRequestPayload::write,
            SortRequestPayload::read);

    private static void write(FriendlyByteBuf buf, SortRequestPayload p) {
        buf.writeUUID(p.requestId);
        buf.writeVarLong(p.clientTick);
        buf.writeBoolean(p.preview);
        buf.writeVarInt(p.radius);
        List<BlockPos> cs = p.candidates;
        int n = Math.min(cs.size(), MAX_CANDIDATES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeBlockPos(cs.get(i));
        }
    }

    private static SortRequestPayload read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        long tick = buf.readVarLong();
        boolean preview = buf.readBoolean();
        int radius = buf.readVarInt();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_CANDIDATES) {
            throw new IllegalArgumentException("bad candidate count " + n);
        }
        List<BlockPos> cs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            cs.add(buf.readBlockPos());
        }
        return new SortRequestPayload(id, tick, preview, cs, radius);
    }

    @Override
    public Type<SortRequestPayload> type() {
        return TYPE;
    }
}
