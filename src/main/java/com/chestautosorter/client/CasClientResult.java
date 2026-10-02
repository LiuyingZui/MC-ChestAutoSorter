package com.chestautosorter.client;

import com.chestautosorter.diagnostic.Diag;
import com.chestautosorter.network.Msg;
import com.chestautosorter.network.SortResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-only reply rendering. Referenced only when the physical side is CLIENT. */
public final class CasClientResult {

    private CasClientResult() {}

    /**
     * Renders a wire message in the receiving player's language. Messages that carry a
     * {@code chestautosorter.*} translation key are resolved here, on the client, so the server
     * never has to guess which language the player uses. Anything else is passed through verbatim.
     */
    private static String resolve(String wire) {
        if (!Msg.isKey(wire)) {
            return wire;
        }
        String[] parts = Msg.split(wire);
        if (parts.length == 1) {
            return Component.translatable(parts[0]).getString();
        }
        Object[] args = new Object[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return Component.translatable(parts[0], args).getString();
    }

    /**
     * A skipped entry travels as {@code (x,y,z)} + {@link Msg#SEP} + the reason key, because the
     * position is language-neutral while the reason has to be read in the player's language.
     */
    private static String resolveSkipped(String wire) {
        int sep = wire.indexOf(Msg.SEP);
        String text = sep < 0
                ? resolve(wire)
                : wire.substring(0, sep) + " " + resolve(wire.substring(sep + 1));
        return Component.translatable("chestautosorter.result.skipped", text).getString();
    }

    /** Server result code for a preview, mirrored from {@code SortService.RESULT_PREVIEW}. */
    private static final int RESULT_PREVIEW = 3;
    private static final int RESULT_OK = 0;

    public static void handle(SortResultPayload payload) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        boolean preview = payload.code() == RESULT_PREVIEW;
        java.util.List<String> feed = new java.util.ArrayList<>();
        for (String line : payload.lines()) {
            feed.add(resolve(line));
        }
        for (String s : payload.skipped()) {
            feed.add(resolveSkipped(s));
        }
        ChestPanel panel = CasClient.openPanel();
        if (panel != null) {
            panel.onResult(payload.requestId(), preview, payload.code() == RESULT_OK || preview, feed);
        }
        for (String line : feed) {
            player.sendSystemMessage(Component.literal("[ChestAutoSorter] " + line));
        }
        Diag.phase(payload.requestId(), "client_result",
                "code=" + payload.code() + " chests=" + payload.chests() + " stacks=" + payload.stacks()
                        + " scanUs=" + payload.scanMicros() + " planUs=" + payload.planMicros()
                        + " commitUs=" + payload.commitMicros());
    }
}
