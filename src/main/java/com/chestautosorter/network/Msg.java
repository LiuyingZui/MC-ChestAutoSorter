package com.chestautosorter.network;

/**
 * Client-facing messages are sent as translation keys rather than rendered text, so the wording
 * follows the receiving player's language instead of the server's. A key is a
 * {@code chestautosorter.*} translation key optionally followed by arguments after a
 * {@link #SEP}. The client resolves it via {@code Component.translatable}.
 *
 * <p>Everything else (free-form diagnostics) passes through unchanged.
 */
public final class Msg {

    public static final char SEP = '\u0001';

    private Msg() {}

    /** Builds a wire message from a translation key and its arguments. */
    public static String key(String translationKey, Object... args) {
        if (args.length == 0) {
            return translationKey;
        }
        StringBuilder sb = new StringBuilder(translationKey);
        for (Object a : args) {
            sb.append(SEP).append(a);
        }
        return sb.toString();
    }

    /** True when the wire message is a translation key this mod knows. */
    public static boolean isKey(String wire) {
        return wire != null && wire.startsWith("chestautosorter.");
    }

    /** Splits a wire message into its translation key and arguments. */
    public static String[] split(String wire) {
        return wire.split(String.valueOf(SEP), -1);
    }
}
