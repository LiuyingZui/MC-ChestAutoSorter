package com.chestautosorter.client;

import com.chestautosorter.CAS;
import com.chestautosorter.diagnostic.Diag;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Development-only visual acceptance harness. Drives the real UI through the real event path and
 * writes screenshots into {@code run/screenshots}, so a layout change can be judged by looking at
 * pixels instead of by reading geometry logs.
 *
 * <p>It stays inert unless {@code -Dcas.ui.shot=<tag>} is on the command line (see the {@code uiShot}
 * project property), and the release jar excludes this class entirely — the shipped mod never sees
 * it. Clicks go through {@link Screen#mouseClicked} at the centre of the same rectangles the panel
 * draws, so what is verified here is the clickable area, not a private shortcut into the logic.
 *
 * <p>A tag that starts with {@code survival} additionally clears creative mode on the client, because
 * the dev player is creative and the survival {@link InventoryScreen} replaces itself with the
 * creative one on its first tick — which is the screen the player in the bug report actually uses.
 */
@EventBusSubscriber(modid = CAS.MOD_ID, value = Dist.CLIENT)
final class UiHarness {

    private UiHarness() {}

    private static final int IDLE = 0;
    private static final int SETTLE = 1;
    private static final int OPEN_INVENTORY = 2;
    private static final int SHOOT_INVENTORY = 3;
    private static final int CLICK_OPEN = 4;
    private static final int SHOOT_PANEL = 5;
    private static final int SELECT_ALL = 6;
    private static final int PREVIEW = 7;
    private static final int SHOOT_PREVIEW = 8;
    private static final int CLICK_CLOSE = 9;
    private static final int VERIFY_CLOSED = 10;
    private static final int DONE = 11;

    private static int step = IDLE;
    private static int ticks;
    private static boolean forcedCreative;
    private static String tag;
    private static String startedAt;

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (step == DONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (step == IDLE) {
            String shot = System.getProperty("cas.ui.shot");
            if (shot == null) {
                step = DONE;
                return;
            }
            if (mc.level == null || mc.player == null || mc.gui.screen() != null) {
                return;
            }
            tag = shot;
            startedAt = java.time.format.DateTimeFormatter.ofPattern("HHmmss")
                    .format(java.time.LocalTime.now());
            Diag.info("ui.harness armed tag=" + tag);
            step = SETTLE;
            ticks = 80;
            return;
        }
        if (ticks > 0) {
            ticks--;
            return;
        }
        advance(mc);
    }

    private static void advance(Minecraft mc) {
        Screen screen = mc.gui.screen();
        switch (step) {
            case SETTLE -> {
                if (tag.startsWith("survival") && mc.player.hasInfiniteMaterials()) {
                    // The player in the bug report runs survival; that is the screen the button has to
                    // work on, so the harness asks for it. forcedCreative says what to undo at the end.
                    mc.player.getAbilities().instabuild = false;
                    forcedCreative = true;
                }
                Diag.info("ui.harness mode instabuild=" + mc.player.hasInfiniteMaterials());
                step = OPEN_INVENTORY;
                ticks = 0;
            }
            case OPEN_INVENTORY -> {
                mc.setScreenAndShow(new InventoryScreen(mc.player));
                Diag.info("ui.harness opened inventory screen="
                        + mc.gui.screen().getClass().getSimpleName());
                step = SHOOT_INVENTORY;
                ticks = 20;
            }
            case SHOOT_INVENTORY -> {
                shoot(mc, "cas-1-inventory.png");
                step = CLICK_OPEN;
                ticks = 20;
            }
            case CLICK_OPEN -> {
                Button open = openButton(screen);
                if (open == null) {
                    fail(mc, "no open button on " + screen.getClass().getSimpleName());
                    return;
                }
                int cx = open.getX() + open.getWidth() / 2;
                int cy = open.getY() + open.getHeight() / 2;
                boolean consumed = click(screen, cx, cy);
                Diag.info("ui.harness button=" + open.getX() + "," + open.getY() + " "
                        + open.getWidth() + "x" + open.getHeight()
                        + " matched=" + matchingButtons(screen)
                        + " clicked=" + cx + "," + cy + " consumed=" + consumed
                        + " now=" + mc.gui.screen().getClass().getSimpleName());
                step = SHOOT_PANEL;
                ticks = 25;
            }
            case SHOOT_PANEL -> {
                ChestPanel panel = CasClient.openPanel();
                if (panel == null) {
                    fail(mc, "clicking the button did not open the panel, screen=" + screen);
                    return;
                }
                Diag.info("ui.harness panel opened entries=" + panel.entryCount()
                        + " host=" + screen.getClass().getSimpleName());
                shoot(mc, "cas-2-panel.png");
                step = SELECT_ALL;
                ticks = 15;
            }
            case SELECT_ALL -> {
                ChestPanel panel = CasClient.openPanel();
                if (panel == null) {
                    fail(mc, "panel closed before select all");
                    return;
                }
                clickPanel(panel.controls().selectAll());
                Diag.info("ui.harness select_all clicked selected=" + panel.selectedCount()
                        + " of " + panel.entryCount());
                step = PREVIEW;
                ticks = 10;
            }
            case PREVIEW -> {
                ChestPanel panel = CasClient.openPanel();
                if (panel == null) {
                    fail(mc, "panel closed before preview");
                    return;
                }
                clickPanel(panel.controls().preview());
                step = SHOOT_PREVIEW;
                ticks = 80; // the round trip is one tick plus network; this is generous on purpose
            }
            case SHOOT_PREVIEW -> {
                ChestPanel panel = CasClient.openPanel();
                Diag.info("ui.harness preview clicked selected="
                        + (panel == null ? -1 : panel.selectedCount()));
                shoot(mc, "cas-3-preview.png");
                step = CLICK_CLOSE;
                ticks = 15;
            }
            case CLICK_CLOSE -> {
                ChestPanel panel = CasClient.openPanel();
                if (panel == null) {
                    fail(mc, "panel closed before the close button was tested");
                    return;
                }
                clickPanel(panel.controls().close());
                step = VERIFY_CLOSED;
                ticks = 10;
            }
            case VERIFY_CLOSED -> {
                if (CasClient.openPanel() != null) {
                    fail(mc, "the close button left the panel on screen");
                    return;
                }
                if (!(screen instanceof AbstractContainerScreen)) {
                    fail(mc, "closing the panel did not return to the inventory, screen=" + screen);
                    return;
                }
                Button back = openButton(screen);
                if (back == null || !back.visible) {
                    fail(mc, "the opener did not come back after the panel closed, found=" + (back != null));
                    return;
                }
                Diag.info("ui.harness closed back_to=" + screen.getClass().getSimpleName());
                shoot(mc, "cas-4-closed.png");
                finish(mc, "done");
            }
            default -> finish(mc, "done");
        }
    }

    private static void fail(Minecraft mc, String why) {
        Diag.warn("ui.harness FAILED " + why);
        finish(mc, "aborted");
    }

    /** Ends the run and puts back whatever the harness had to change to reach the right screen. */
    private static void finish(Minecraft mc, String why) {
        if (forcedCreative && mc.player != null) {
            mc.player.getAbilities().instabuild = true;
            forcedCreative = false;
        }
        step = DONE;
        Diag.info("ui.harness " + why);
    }

    private static int matchingButtons(Screen screen) {
        return openButtons(screen).size();
    }

    private static Button openButton(Screen screen) {
        List<Button> found = openButtons(screen);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Every widget on the screen carrying our translated label, which is how a player finds it. */
    private static List<Button> openButtons(Screen screen) {
        String wanted = Component.translatable("chestautosorter.button.open").getString();
        List<Button> found = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && button.getMessage().getString().equals(wanted)) {
                found.add(button);
            }
        }
        return found;
    }

    /**
     * One left click, pressed and released on the same pixel, through the screen's own dispatch.
     *
     * @return whether the screen reported it handled the press
     */
    private static boolean click(Screen screen, int cx, int cy) {
        double x = cx + 0.5;
        double y = cy + 0.5;
        MouseButtonInfo left = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);
        boolean consumed = screen.mouseClicked(new MouseButtonEvent(x, y, left), false);
        screen.mouseReleased(new MouseButtonEvent(x, y, left));
        return consumed;
    }

    /**
     * A click inside the panel, through the same entry point {@link CasClient}'s mouse event handler
     * uses. The panel is not a child of the inventory screen any more, so {@link Screen#mouseClicked}
     * cannot reach it.
     */
    private static boolean clickPanel(ChestPanel.Rect box) {
        return CasClient.clickPanel(box.x() + box.w() / 2.0 + 0.5, box.y() + box.h() / 2.0 + 0.5,
                InputConstants.MOUSE_BUTTON_LEFT);
    }

    /**
     * Writes a screenshot under a name that can never collide with an earlier run: the tag and the
     * start time are part of it. Evidence is only useful while it accumulates, so nothing here ever
     * needs deleting.
     */
    private static void shoot(Minecraft mc, String name) {
        String file = tag + "-" + startedAt + "-" + name;
        Diag.info("ui.harness shot " + new File("screenshots", file).getAbsolutePath());
        Screenshot.grab(new File("."), file, mc.gameRenderer.mainRenderTarget(), 1,
                message -> Diag.info("ui.harness shot_result " + file + " " + message.getString()));
    }
}
