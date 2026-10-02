package com.chestautosorter;

import com.chestautosorter.config.CasConfig;
import com.chestautosorter.diagnostic.Diag;
import com.chestautosorter.network.SortRequestPayload;
import com.chestautosorter.network.SortResultPayload;
import com.chestautosorter.server.SortService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(CAS.MOD_ID)
public final class ChestAutoSorter {

    /** Where the development test scaffolding would sit inside this mod's own artifact. */
    private static final String GAME_TESTS_ENTRY = "com/chestautosorter/gametest/CasGameTests.class";

    public ChestAutoSorter(IEventBus modBus, ModContainer container) {
        CasConfig.register(container);
        registerGameTestsIfPresent(modBus);
        modBus.addListener(ChestAutoSorter::onRegisterPayloads);
        Diag.info("[ChestAutoSorter] constructed on 26.3 / NeoForge " + net.neoforged.neoforge.common.NeoForgeVersion.getVersion());
    }

    /**
     * GameTest scaffolding is development-only and the release jar excludes that package. Presence is
     * decided by looking inside this mod's own code source rather than by letting Class.forName answer:
     * a classpath that also carries the compiled classes directory resolves the test class from the
     * wrong copy, whose static initialisers run against an unbootstrapped second set of Minecraft
     * classes. A broken development seam must never keep a player from loading the mod.
     */
    private static void registerGameTestsIfPresent(IEventBus modBus) {
        java.net.URL source = ChestAutoSorter.class.getProtectionDomain().getCodeSource().getLocation();
        Diag.info("[ChestAutoSorter] codeSource=" + source);
        if (!gameTestsPackaged(source)) {
            Diag.debug("[ChestAutoSorter] gametest classes not packaged; skipping test registration");
            return;
        }
        try {
            Class.forName("com.chestautosorter.gametest.CasGameTests")
                    .getMethod("register", IEventBus.class)
                    .invoke(null, modBus);
        } catch (Throwable failure) {
            Diag.error("[ChestAutoSorter] gametest registration failed, continuing without tests: " + failure);
        }
    }

    private static boolean gameTestsPackaged(java.net.URL source) {
        if (source == null || !"file".equals(source.getProtocol())) {
            return false;
        }
        java.io.File path = new java.io.File(source.getPath());
        if (path.isDirectory()) {
            return new java.io.File(path, GAME_TESTS_ENTRY).isFile();
        }
        if (!path.getName().endsWith(".jar")) {
            return false;
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(path)) {
            return jar.getJarEntry(GAME_TESTS_ENTRY) != null;
        } catch (java.io.IOException unreadable) {
            Diag.warn("[ChestAutoSorter] cannot read " + path + " (" + unreadable + ")");
            return false;
        }
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(SortRequestPayload.TYPE, SortRequestPayload.CODEC, SortService::onRequest);
        registrar.playToClient(SortResultPayload.TYPE, SortResultPayload.CODEC, SortService::onResultClient);
        Diag.info("[ChestAutoSorter] payloads registered (sort_request -> server, sort_result -> client)");
    }
}
