package com.chestautosorter.network;

import com.chestautosorter.CAS;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server -> client result. {@code lines} are already-localised (zh_cn) display strings. */
public record SortResultPayload(UUID requestId, int code, List<String> lines, List<String> skipped,
                                long scanMicros, long planMicros, long commitMicros, int chests, int stacks)
        implements CustomPacketPayload {

    public static final Type<SortResultPayload> TYPE = new Type<>(CAS.id("sort_result"));

    public static final StreamCodec<FriendlyByteBuf, SortResultPayload> CODEC = StreamCodec.of(
            SortResultPayload::write,
            SortResultPayload::read);

    private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeUtf(s, 32767);
        }
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf(32767));
        }
        return out;
    }

    private static void write(FriendlyByteBuf buf, SortResultPayload p) {
        buf.writeUUID(p.requestId);
        buf.writeVarInt(p.code);
        writeStringList(buf, p.lines);
        writeStringList(buf, p.skipped);
        buf.writeVarLong(p.scanMicros);
        buf.writeVarLong(p.planMicros);
        buf.writeVarLong(p.commitMicros);
        buf.writeVarInt(p.chests);
        buf.writeVarInt(p.stacks);
    }

    private static SortResultPayload read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int code = buf.readVarInt();
        List<String> lines = readStringList(buf);
        List<String> skipped = readStringList(buf);
        long scan = buf.readVarLong();
        long plan = buf.readVarLong();
        long commit = buf.readVarLong();
        int chests = buf.readVarInt();
        int stacks = buf.readVarInt();
        return new SortResultPayload(id, code, lines, skipped, scan, plan, commit, chests, stacks);
    }

    @Override
    public Type<SortResultPayload> type() {
        return TYPE;
    }
}
