package com.chestautosorter.client;

import com.chestautosorter.CAS;
import com.chestautosorter.diagnostic.Diag;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Client entry point.
 *
 * <p>This mod registers <em>no</em> key mapping of its own. The sorting panel is opened from a button
 * appended to the player's inventory screen through {@link ScreenEvent.Init.Post}, which fires after
 * the screen has built its widgets and exposes {@code addListener}. The inventory key therefore stays
 * entirely vanilla, and rebinding it keeps working.
 *
 * <p>The panel is <em>not</em> a screen. It floats over its host screen by drawing in
 * {@link ScreenEvent.Render.Post} and by taking the mouse through the cancellable
 * {@link ScreenEvent.MouseButtonPressed.Pre} family. That is what keeps
 * {@code Minecraft.getInstance().screen} the inventory screen while the panel is up, and other mods
 * ask that screen who is open: JEI resolves every hotkey and every overlay click through
 * {@code minecraft.screen}, and it has no handler for a screen it does not know, so a panel that was
 * its own screen switched JEI's recipe key off for as long as it stayed open.
 *
 * <p>Drawing in {@code Render.Post} is still above another mod's overlay, because {@code Post} is
 * fired after the screen, its tooltips and its {@code Render.Foreground} listeners — and JEI paints
 * its ingredient list in {@code Foreground} (at {@code LOWEST} priority, after calling
 * {@code nextStratum()} itself). The stratum this class opens before drawing keeps the panel's text
 * in a later batch than anything painted before it, which is the same trick the game uses between
 * its own screen layers.
 */
@EventBusSubscriber(modid = CAS.MOD_ID, value = Dist.CLIENT)
public final class CasClient {

    private CasClient() {}

    /** The floating panel, or {@code null} while it is closed. */
    private static ChestPanel panel;
    /** The screen the panel was opened on; it draws and takes the mouse over this one only. */
    private static AbstractContainerScreen<?> host;
    /** The inventory button that opened the panel. Hidden while the panel is up. */
    private static Button opener;

    /** The live panel, for the response handler and the development harness. */
    public static ChestPanel openPanel() {
        return panel;
    }

    /**
     * Appends the open button to the player's inventory screen.
     *
     * <p>Two guards matter. First, only the screen the player is actually looking at is eligible: in
     * creative mode the game constructs throwaway {@code InventoryScreen}s while tearing the creative
     * screen down, and attaching to one of those would put the button on a screen that is never shown.
     * Second, both inventories are covered — {@code CreativeModeInventoryScreen} does not extend
     * {@code InventoryScreen} and uses its own {@code ItemPickerMenu}, so the check is "this screen
     * carries the player's own inventory slot range", not a class comparison.
     *
     * <p>This also runs when a screen rebuilds its widgets — a window resize, or a creative tab
     * change — so a panel that is already up adopts the new button and re-fits itself instead of
     * keeping a geometry for the old window and an opener nobody can click.
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (Minecraft.getInstance().gui.screen() != screen) {
            return; // a transient screen being replaced: never the one on display
        }
        if (!carriesPlayerInventory(screen.getMenu())) {
            return;
        }
        Button button = openButton(screen);
        if (screen == host && panel != null) {
            opener = button;
            button.visible = false;
            panel.layout(screen.width, screen.height);
        }
        event.addListener(button);
    }

    /** How far the creative tab bar reaches out from the container image. */
    private static final int TAB_STRIP = 32;

    /**
     * The button that opens the panel, sized from its own translated label.
     *
     * <p>Placement walks the bands around the container image and takes the first one that is
     * genuinely free, where free is <em>checked</em> rather than assumed: the band has to be on
     * screen and clear of everything that already answers clicks there. Left comes before right
     * because the ingredient overlays players actually run paint over the right side.
     *
     * <p>The first version of this put the button above the image whenever it fitted. On the creative
     * screen that band is the tab bar, and {@code CreativeModeInventoryScreen.mouseClicked} answers a
     * tab hit with {@code return true} without ever reaching the widget list — the button was drawn,
     * looked normal, and did nothing at all.
     */
    private static Button openButton(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        Component label = Component.translatable("chestautosorter.button.open");
        int margin = 2;
        int gap = 4;
        int h = 20;
        int w = Math.min(mc.font.width(label) + 8, screen.width - 2 * margin);

        int invLeft = screen.getLeftPos();
        int invTop = screen.getTopPos();
        int invRight = invLeft + screen.getImageWidth();
        int invBottom = invTop + screen.getImageHeight();

        String[] names = {"above-left", "above-right", "below-left", "below-right", "left", "right", "corner"};
        int[][] spots = {
            {invLeft, invTop - gap - h}, {invRight - w, invTop - gap - h},
            {invLeft, invBottom + gap}, {invRight - w, invBottom + gap},
            {invLeft - gap - w, invTop}, {invRight + gap, invTop},
            {margin, margin},
        };

        List<Block> blocks = blocks(screen);
        StringBuilder rejected = new StringBuilder();
        int chosen = spots.length - 1;
        for (int i = 0; i < spots.length; i++) {
            String why = whyUnavailable(spots[i][0], spots[i][1], w, h, margin, screen, blocks);
            if (why == null) {
                chosen = i;
                break;
            }
            if (!rejected.isEmpty()) {
                rejected.append(' ');
            }
            rejected.append(names[i]).append('=').append(why);
        }

        Diag.info("ui.button spot=" + names[chosen] + " bounds=" + spots[chosen][0] + "," + spots[chosen][1]
                + " " + w + "x" + h
                + " inv=" + invLeft + "," + invTop + " " + screen.getImageWidth() + "x" + screen.getImageHeight()
                + " screen=" + screen.width + "x" + screen.height
                + " label=[" + label.getString() + "]"
                + (rejected.isEmpty() ? "" : " rejected[" + rejected + "]"));

        Button button = Button.builder(label, b -> showPanel(screen, b))
                .bounds(spots[chosen][0], spots[chosen][1], w, h)
                .tooltip(Tooltip.create(Component.translatable("chestautosorter.button.open.tooltip")))
                .build();
        // The container screen rebuilds its widgets when a creative tab changes; a fresh button made
        // while the panel is up must not appear behind it.
        button.visible = panel == null;
        return button;
    }

    /** Opens the panel over {@code screen}. The button hides itself; the panel's own close action shows it. */
    private static void showPanel(AbstractContainerScreen<?> screen, Button button) {
        ChestPanel fresh = new ChestPanel(CasClient::hidePanel);
        fresh.scan();
        fresh.layout(screen.width, screen.height);
        panel = fresh;
        host = screen;
        opener = button;
        button.visible = false;
        Diag.info("ui.open host=" + screen.getClass().getSimpleName()
                + " entries=" + fresh.entryCount()
                + " screen=" + screen.width + "x" + screen.height);
    }

    /** Closes the panel and gives the inventory its button back. Idempotent. */
    private static void hidePanel() {
        if (panel == null) {
            return;
        }
        panel = null;
        host = null;
        Button button = opener;
        opener = null;
        if (button != null) {
            button.visible = true;
        }
        Diag.info("ui.close panel hidden, opener restored=" + (button != null));
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (panel == null || event.getScreen() != host) {
            return;
        }
        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        // Everything already painted this frame — the inventory, its tooltips, and any overlay a mod
        // draws in Render.Foreground such as JEI's ingredient list — sits in earlier strata. Opening a
        // new one keeps the panel's own text in the same late batch as its background.
        graphics.nextStratum();
        panel.extract(graphics, event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    /**
     * Takes a click that lands on the panel.
     *
     * <p>{@code HIGHEST} priority is not decoration: with the inventory still the active screen, other
     * mods answer this event too, and JEI reads the click against its own overlay. A click inside the
     * panel must never also be a click on a slot, an ingredient or a ghost-drop target.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!overPanel(event.getMouseX(), event.getMouseY())) {
            return;
        }
        clickPanel(event.getMouseX(), event.getMouseY(), event.getButton());
        event.setCanceled(true);
    }

    /** A press that belongs to the panel never becomes a release or a drag for the screen below. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButtonReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (overPanel(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    /** Dragging over the panel is the inventory's drag gesture arriving on top of us; it stops here. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (overPanel(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!overPanel(event.getMouseX(), event.getMouseY())) {
            return;
        }
        scrollPanel(event.getMouseX(), event.getMouseY(), event.getScrollDeltaY());
        event.setCanceled(true);
    }

    /**
     * Hides the item tooltip the inventory would otherwise draw for the slot under the panel. The panel
     * is opaque, but a tooltip wide enough to leave its edge reads as a leak from behind it.
     */
    @SubscribeEvent
    public static void onTooltip(RenderTooltipEvent.Pre event) {
        if (panel == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.getWindow().getGuiScale();
        if (overPanel(mc.mouseHandler.xpos() / scale, mc.mouseHandler.ypos() / scale)) {
            event.setCanceled(true);
        }
    }

    /** The panel belongs to one screen only: when that screen goes away, so does the panel. */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == host) {
            hidePanel();
        }
    }

    /** A new screen — a chest's own GUI, JEI's recipe page — takes over; the panel must not float on. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (panel != null && event.getNewScreen() != host) {
            hidePanel();
        }
    }

    private static boolean overPanel(double mx, double my) {
        ChestPanel open = panel;
        return open != null && open.isMouseOver(mx, my);
    }

    /**
     * Routes a click into the panel exactly as {@link #onMouseButton} does, for the development
     * harness. Only the left button acts; a right or middle button over the panel is still swallowed by
     * the caller.
     */
    public static boolean clickPanel(double mx, double my, int button) {
        ChestPanel open = panel;
        if (open == null || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        return open.mouseClicked(new MouseButtonEvent(mx, my, new MouseButtonInfo(button, 0)), false);
    }

    /** Routes a scroll into the panel as {@link #onMouseScrolled} does. */
    public static boolean scrollPanel(double mx, double my, double scrollY) {
        ChestPanel open = panel;
        return open != null && open.mouseScrolled(mx, my, 0, scrollY);
    }

    /** Everything on this screen that swallows a click before our button could get one. */
    private static List<Block> blocks(AbstractContainerScreen<?> screen) {
        List<Block> out = new ArrayList<>();
        int left = screen.getLeftPos();
        int top = screen.getTopPos();
        int width = screen.getImageWidth();
        int height = screen.getImageHeight();
        if (screen instanceof CreativeModeInventoryScreen) {
            // The tab bar is hit-tested by the screen itself, so it is not in the widget list and
            // cannot be found by asking the children. It sits above and below the image.
            out.add(new Block(left, top - TAB_STRIP, width, TAB_STRIP, "creative_tabs"));
            out.add(new Block(left, top + height, width, TAB_STRIP, "creative_tabs"));
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                out.add(new Block(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(),
                        "widget:" + widget.getClass().getSimpleName()));
            }
        }
        return out;
    }

    /** Null when the rectangle is a spot the player can actually click. */
    private static String whyUnavailable(int x, int y, int w, int h, int margin,
                                         AbstractContainerScreen<?> screen, List<Block> blocks) {
        if (x < margin || y < margin || x + w > screen.width - margin || y + h > screen.height - margin) {
            return "offscreen";
        }
        for (Block block : blocks) {
            if (block.covers(x, y, w, h)) {
                return block.what();
            }
        }
        return null;
    }

    /** A rectangle some other control already answers clicks for. */
    private record Block(int x, int y, int w, int h, String what) {
        boolean covers(int ox, int oy, int ow, int oh) {
            return ox < x + w && x < ox + ow && oy < y + h && y < oy + oh;
        }
    }

    /**
     * True when the menu is the player's own inventory menu (survival) or the creative picker that
     * embeds the same inventory slots.
     */
    private static boolean carriesPlayerInventory(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        if (menu == mc.player.inventoryMenu) {
            return true;
        }
        return menu instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.ItemPickerMenu;
    }
}
