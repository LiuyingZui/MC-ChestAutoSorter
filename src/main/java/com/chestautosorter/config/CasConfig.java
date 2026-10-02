package com.chestautosorter.config;

import com.chestautosorter.diagnostic.Diag;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class CasConfig {

    public static final ModConfigSpec SPEC;
    private static final Server SERVER;

    static {
        Pair<Server, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = pair.getLeft();
        SPEC = pair.getRight();
    }

    private CasConfig() {}

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    public static Server server() {
        return SERVER;
    }

    public static final class Server {
        public final ModConfigSpec.IntValue radius;
        public final ModConfigSpec.IntValue maxContainers;
        public final ModConfigSpec.IntValue cooldownMs;
        public final ModConfigSpec.BooleanValue protectCustomNamed;
        public final ModConfigSpec.BooleanValue allowMixedBoxes;
        public final ModConfigSpec.BooleanValue debug;

        Server(ModConfigSpec.Builder b) {
            b.comment("Chest Auto Sorter server settings (single player / integrated server).").push("general");
            radius = b.comment("Max distance (blocks) from the player to a chest for it to be considered.")
                    .defineInRange("radius", 12, 1, 64);
            maxContainers = b.comment("Max logical containers processed in one request.")
                    .defineInRange("max_containers", 64, 1, 512);
            cooldownMs = b.comment("Minimum milliseconds between two accepted requests.")
                    .defineInRange("cooldown_ms", 1000, 0, 60000);
            protectCustomNamed = b.comment("Skip chests that have a custom name (renamed) by default.")
                    .define("protect_custom_named", true);
            allowMixedBoxes = b.comment("If dedicated per-category boxes do not fit, allow mixing categories.")
                    .define("allow_mixed_boxes", true);
            debug = b.comment("Verbose per-slot logging of inventory snapshots.")
                    .define("debug", false);
            b.pop();
        }
    }

    public static void syncDebugFlag() {
        Diag.setDebug(SERVER.debug.get());
    }
}
