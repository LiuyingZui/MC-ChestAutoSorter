package com.chestautosorter.client;

import com.chestautosorter.config.CasConfig;
import com.chestautosorter.diagnostic.Diag;
import com.chestautosorter.network.SortRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The chest-selection panel, floated over the inventory by {@link CasClient}.
 *
 * <p>It used to be attached straight onto the inventory screen, which put it at the mercy of anything
 * drawn after it — a recipe overlay from another mod covered its header and list. It is drawn in
 * {@code ScreenEvent.Render.Post} now, which comes after every {@code Render.Foreground} overlay, and
 * it is deliberately <em>not</em> a screen of its own: mods like JEI resolve their hotkeys against
 * {@code minecraft.screen}, and a panel that replaced the inventory switched them off.
 *
 * <p>The candidate set is frozen when the panel opens: changing orientation means closing and
 * reopening it, and moving the mouse over it never re-aims world selection.
 *
 * <p>All text is drawn from lang keys at a reduced scale via the graphics pose, so the panel follows
 * the player's language and stays compact without touching the global GUI scale. Every drawn
 * rectangle comes from the same {@code *Rect} helpers used for hit-testing, so a label can never be
 * drawn somewhere its click area is not.
 */
final class ChestPanel extends AbstractWidget {

    /** Preferred width; the panel gives up width before it would ever cover the inventory. */
    private static final int FULL_WIDTH = 168;
    private static final int MIN_WIDTH = 112;
    /** Inner padding from the panel edge to content. */
    private static final int GAP_X = 6;
    /** Padding between a button's label and its border. */
    private static final int PAD_X = 4;
    private static final int BTN_H = 14;

    private static final float S_TITLE = 0.95F;
    private static final float S_BODY = 0.87F;
    private static final float S_SMALL = 0.76F;

    private static final int HEADER_H = 34;
    /** One extra header line, reserved only while the last scan actually filtered something out. */
    private static final int FILTER_LINE_H = 9;
    private static final int ROW_H = 20;
    private static final int FULL_VISIBLE_ROWS = 5;
    /** Gap, select row, gap, preview line, action row gap, action row, gap, two status lines. */
    private static final int CONTROLS_H = 2 + BTN_H + 3 + 11 + BTN_H + 3 + 18;

    private static final String K = "chestautosorter.panel.";

    /** One logical container; double chests are represented once, by a canonical half. */
    record Entry(BlockPos canonical, boolean doubleChest, String kindKey, int distanceBlocks, String dirKey) {}

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static String flat(Component c) {
        return c.getString();
    }

    private final Runnable closeAction;
    private final List<Entry> entries = new ArrayList<>();
    private final LinkedHashSet<BlockPos> selected = new LinkedHashSet<>();
    private final List<String> statusLines = new ArrayList<>();

    private int scroll;
    private boolean busy;
    private boolean previewValid;
    private UUID planId;
    /** Why the list is shorter than the chests nearby, from the last scan. */
    private int hidden;
    private int tooFar;
    private int offscreen;
    private int occluded;
    /** Visible row count, trimmed to whatever vertical space the window actually leaves. */
    private int visibleRows = FULL_VISIBLE_ROWS;
    /** Last host size, kept so 刷新 can re-run the identical layout without the screen at hand. */
    private int screenWidth = FULL_WIDTH;
    private int screenHeight = HEADER_H + FULL_VISIBLE_ROWS * ROW_H + CONTROLS_H;

    ChestPanel(Runnable closeAction) {
        super(0, 0, FULL_WIDTH, HEADER_H + FULL_VISIBLE_ROWS * ROW_H + CONTROLS_H,
                tr("chestautosorter.panel.title"));
        this.closeAction = closeAction;
    }

    int entryCount() {
        return entries.size();
    }

    int selectedCount() {
        return selected.size();
    }

    // --- lifecycle -------------------------------------------------------------------------

    /** Freezes the candidate list using the world camera as it is when the inventory opens. */
    void scan() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        entries.clear();
        hidden = tooFar = offscreen = occluded = 0;
        if (level == null || player == null) {
            return;
        }
        int radius = CasConfig.server().radius.get();
        VisibleChestSelector.Result result = VisibleChestSelector.select(radius);
        LinkedHashSet<BlockPos> seen = new LinkedHashSet<>();
        for (BlockPos pos : result.candidates) {
            BlockPos canonical = canonicalAnchor(level, pos);
            if (!seen.add(canonical)) {
                continue;
            }
            BlockState state = level.getBlockState(canonical);
            boolean dbl = state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE;
            double dist = Math.sqrt(bounds(level, canonical).distanceToSqr(player.getEyePosition()));
            entries.add(new Entry(canonical, dbl, kindOf(state), (int) Math.round(dist),
                    relativeDir(player, bounds(level, canonical).getCenter())));
        }
        hidden = Math.max(0, result.scanned - entries.size());
        tooFar = result.tooFar;
        offscreen = result.offscreen;
        occluded = result.occluded;
        selected.removeIf(p -> !seen.contains(p));
        previewValid = false;
        planId = null;
    }

    /**
     * Centres the panel in its host screen and trims width and row count to what the window actually
     * leaves, so a small canvas or a big GUI scale narrows the panel instead of pushing its buttons
     * off screen. Rows give way before the control block does: 全选 / 预览 / 整理 must stay reachable.
     */
    void layout(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        int margin = 4;
        int width = Math.max(MIN_WIDTH, Math.min(FULL_WIDTH, screenWidth - 2 * margin));

        int rows = (screenHeight - 2 * margin - headerH() - CONTROLS_H) / ROW_H;
        visibleRows = Math.max(1, Math.min(FULL_VISIBLE_ROWS, rows));
        int height = headerH() + visibleRows * ROW_H + CONTROLS_H;

        setWidth(width);
        setHeight(height);
        setX((screenWidth - width) / 2);
        setY((screenHeight - height) / 2);
        if (scroll + visibleRows > entries.size()) {
            scroll = Math.max(0, entries.size() - visibleRows);
        }
        logGeometry(screenWidth, screenHeight);
    }

    /**
     * Geometry dump on every relayout. The panel is no longer tied to a screen instance, so a resize
     * re-fits it instead of a new panel being minted; the probe reads the <em>last</em> line and must
     * not be handed stale rectangles.
     */
    private void logGeometry(int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        boolean inside = getX() >= 0 && getY() >= 0
                && getX() + getWidth() <= screenWidth && getY() + getHeight() <= screenHeight;
        Controls c = controls();
        Diag.info("ui.geom screen=" + screenWidth + "x" + screenHeight
                + " guiScale=" + mc.getWindow().getGuiScale()
                + " host=" + (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())
                + " panel=" + getX() + "," + getY() + " " + getWidth() + "x" + getHeight()
                + " rows=" + visibleRows + " insideWindow=" + inside
                + " listTop=" + listTop() + " listBottom=" + listBottom()
                + " controlsTop=" + controlsTop() + " bottom=" + (getY() + getHeight())
                + " entries=" + entries.size() + " selected=" + selected.size()
                + " hidden=" + hidden + " tooFar=" + tooFar + " offscreen=" + offscreen
                + " occluded=" + occluded
                + " title=[" + flat(tr(K + "title")) + "]"
                + " selectAll=[" + flat(tr(K + "select_all")) + "]"
                + " commit=[" + flat(tr(K + "commit")) + "]"
                + " close=[" + flat(tr(K + "close")) + "]"
                + " empty=[" + flat(tr(K + "empty")) + "]"
                // Live hit-boxes for the black-box probe, so an OS-level click can aim at the same
                // pixels the drawing code uses.
                + " rects[refresh=" + at(c.refresh()) + " close=" + at(c.close())
                + " selectAll=" + at(c.selectAll()) + " clear=" + at(c.clear())
                + " preview=" + at(c.preview()) + " commit=" + at(c.commit()) + "]");
    }

    /** The filter line exists only while the last scan actually dropped a chest, so the header grows. */
    private int headerH() {
        return hidden > 0 ? HEADER_H + FILTER_LINE_H : HEADER_H;
    }

    private int listTop() {
        return getY() + headerH();
    }

    private int listBottom() {
        return listTop() + visibleRows * ROW_H;
    }

    private int controlsTop() {
        return listBottom() + 2;
    }

    private int previewLineY() {
        return controlsTop() + BTN_H + 3;
    }

    private int actionRowY() {
        return previewLineY() + 11;
    }

    private int statusRowY() {
        return actionRowY() + BTN_H + 3;
    }

    // --- selection -------------------------------------------------------------------------

    void selectAllAvailable() {
        for (Entry e : entries) {
            selected.add(e.canonical());
        }
        invalidatePreview();
    }

    void clearSelection() {
        selected.clear();
        invalidatePreview();
    }

    void refresh() {
        scan();
        layout(screenWidth, screenHeight);
    }

    private void invalidatePreview() {
        previewValid = false;
        planId = null;
        statusLines.clear();
    }

    // --- scaled text -----------------------------------------------------------------------

    /** Width of a string once drawn at the given scale, in screen pixels. */
    private static int sw(Font font, String s, float scale) {
        return Math.round(font.width(s) * scale);
    }

    /**
     * Draws text at a reduced size. The scale lives on this widget's pose only, so neither the
     * vanilla inventory nor other mods is affected, and the global GUI scale stays untouched.
     */
    private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /** Right-aligned scaled text; {@code right} is the screen x the text must not exceed. */
    private static void textRight(GuiGraphicsExtractor g, Font font, String s, int right, int y, int color, float scale) {
        text(g, font, s, right - sw(font, s, scale), y, color, scale);
    }

    private static int buttonWidth(Font font, String label) {
        return sw(font, label, S_BODY) + 2 * PAD_X;
    }

    /** Clip to a width expressed in screen pixels at the given scale. */
    private static String clipScaled(Font font, String s, int screenPx, float scale) {
        return clip(font, s, (int) (screenPx / scale));
    }

    // --- control geometry (shared by drawing and hit-testing) ------------------------------

    record Rect(int x, int y, int w, int h) {}

    private static String at(Rect r) {
        return r.x() + "," + r.y() + " " + r.w() + "x" + r.h();
    }

    /** Close sits in the corner; refresh steps in from its left edge. */
    Rect closeRect(Font font) {
        int w = buttonWidth(font, flat(tr(K + "close")));
        return new Rect(getX() + getWidth() - GAP_X - w, getY() + 3, w, BTN_H);
    }

    Rect refreshRect(Font font) {
        int w = buttonWidth(font, flat(tr(K + "refresh")));
        return new Rect(closeRect(font).x() - 3 - w, getY() + 3, w, BTN_H);
    }

    Rect selectAllRect(Font font) {
        return new Rect(getX() + GAP_X, controlsTop(),
                buttonWidth(font, flat(tr(K + "select_all"))), BTN_H);
    }

    Rect clearRect(Font font) {
        String label = flat(tr(K + "clear"));
        return new Rect(getX() + getWidth() - GAP_X - buttonWidth(font, label), controlsTop(),
                buttonWidth(font, label), BTN_H);
    }

    private String previewLabel() {
        return flat(tr(K + (previewValid ? "repreview" : "preview")));
    }

    Rect previewRect(Font font) {
        return new Rect(getX() + GAP_X, actionRowY(), buttonWidth(font, previewLabel()), BTN_H);
    }

    Rect commitRect(Font font) {
        String label = flat(tr(K + "commit"));
        return new Rect(getX() + getWidth() - GAP_X - buttonWidth(font, label), actionRowY(),
                buttonWidth(font, label), BTN_H);
    }

    record Controls(Rect refresh, Rect close, Rect selectAll, Rect clear, Rect preview, Rect commit) {}

    /**
     * Every control's live rectangle, straight from the helpers the drawing uses. The development
     * screenshot harness clicks through these, so an automated run hits the same area a player does.
     */
    Controls controls() {
        Font font = Minecraft.getInstance().font;
        return new Controls(refreshRect(font), closeRect(font), selectAllRect(font), clearRect(font),
                previewRect(font), commitRect(font));
    }

    // --- rendering -------------------------------------------------------------------------

    /**
     * Paints the panel into the extractor the host screen is being drawn with. {@link CasClient} calls
     * this from {@code ScreenEvent.Render.Post}; the widget is never in a screen's child list, so the
     * normal render path would never reach it.
     */
    void extract(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        extractWidgetRenderState(g, mx, my, partialTick);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        Font font = Minecraft.getInstance().font;
        g.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY() + getHeight() + 1, 0xFF404040);
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xF0101010);

        drawHeader(g, font, mx, my);
        drawList(g, font, mx, my);
        drawControls(g, font, mx, my);
    }

    /** Title, refresh and close share a row; range and found/selected get their own rows, plus a
     *  fourth one only while the last scan filtered a chest out. */
    private void drawHeader(GuiGraphicsExtractor g, Font font, int mx, int my) {
        int radius = CasConfig.server().radius.get();
        Rect refresh = refreshRect(font);
        int titleRoom = Math.max(8, refresh.x() - 4 - (getX() + GAP_X));
        text(g, font, clipScaled(font, flat(tr(K + "title")), titleRoom, S_TITLE), getX() + GAP_X, getY() + 4,
                0xFFFFFFFF, S_TITLE);
        button(g, font, refresh, flat(tr(K + "refresh")), true, mx, my);
        button(g, font, closeRect(font), flat(tr(K + "close")), true, mx, my);

        String range = flat(tr(K + "range", radius)) + (busy ? "  " + flat(tr(K + "busy")) : "");
        text(g, font, clipScaled(font, range, getWidth() - 2 * GAP_X, S_BODY), getX() + GAP_X, getY() + 15,
                0xFFB8B8B8, S_BODY);

        text(g, font, flat(tr(K + "found", entries.size())), getX() + GAP_X, getY() + 24, 0xFFB8B8B8, S_BODY);
        textRight(g, font, flat(tr(K + "selected", selected.size())), getX() + getWidth() - GAP_X, getY() + 24,
                0xFFB8B8B8, S_BODY);

        if (hidden > 0) {
            text(g, font, clipScaled(font, flat(tr(K + "filtered", hidden, filterDetail())),
                    getWidth() - 2 * GAP_X, S_BODY), getX() + GAP_X, getY() + 33, 0xFF909090, S_BODY);
        }
    }

    /** Rejection reasons with a non-zero count, so the line never reads "视野外 0". */
    private String filterDetail() {
        List<String> parts = new ArrayList<>(3);
        if (tooFar > 0) {
            parts.add(flat(tr(K + "filtered_too_far", tooFar)));
        }
        if (offscreen > 0) {
            parts.add(flat(tr(K + "filtered_offscreen", offscreen)));
        }
        if (occluded > 0) {
            parts.add(flat(tr(K + "filtered_occluded", occluded)));
        }
        return String.join(" · ", parts);
    }

    /** Scrollable rows. The scissor is pushed and popped symmetrically around this block only. */
    private void drawList(GuiGraphicsExtractor g, Font font, int mx, int my) {
        g.fill(getX(), listTop() - 1, getX() + getWidth(), listTop(), 0xFF606060);
        g.enableScissor(getX(), listTop(), getX() + getWidth(), listBottom());
        try {
            if (entries.isEmpty()) {
                text(g, font, flat(tr(K + "empty")), getX() + GAP_X, listTop() + 5, 0xFF909090, S_BODY);
            } else {
                int first = scroll;
                int last = Math.min(entries.size(), first + visibleRows);
                for (int i = first; i < last; i++) {
                    drawRow(g, font, i, entries.get(i), listTop() + (i - first) * ROW_H, mx, my);
                }
            }
        } finally {
            g.disableScissor();
        }
        g.fill(getX(), listBottom(), getX() + getWidth(), listBottom() + 1, 0xFF606060);
    }

    /** Selection row, preview line, action buttons and status. Fixed: never scrolls with the list. */
    private void drawControls(GuiGraphicsExtractor g, Font font, int mx, int my) {
        button(g, font, selectAllRect(font), flat(tr(K + "select_all")), true, mx, my);
        button(g, font, clearRect(font), flat(tr(K + "clear")), true, mx, my);

        text(g, font, clipScaled(font, previewSummary(), getWidth() - 2 * GAP_X, S_BODY), getX() + GAP_X,
                previewLineY(), previewValid ? 0xFF9FE0FF : 0xFF909090, S_BODY);

        button(g, font, previewRect(font), previewLabel(), canAct(), mx, my);
        button(g, font, commitRect(font), flat(tr(K + "commit")), canAct() && previewValid && !busy, mx, my);

        drawStatus(g, font, statusRowY());
    }

    /** Up to two wrapped lines inside the panel; a third line would run off the screen. */
    private void drawStatus(GuiGraphicsExtractor g, Font font, int y) {
        List<String> lines = wrap(font, statusText(), getWidth() - 2 * GAP_X, S_BODY, 2);
        int color = statusColor();
        for (int i = 0; i < lines.size(); i++) {
            text(g, font, lines.get(i), getX() + GAP_X, y + (int) Math.round(9 * S_BODY) * i, color, S_BODY);
        }
    }

    /** One line describing the current preview, or a placeholder with the reason it is missing. */
    private String previewSummary() {
        if (!statusLines.isEmpty()) {
            return statusLines.get(0);
        }
        if (busy) {
            return flat(tr(K + "preview_waiting"));
        }
        return flat(tr(K + "preview_none"));
    }

    private void drawRow(GuiGraphicsExtractor g, Font font, int index, Entry e, int rowY, int mx, int my) {
        if (mx >= getX() && mx < getX() + getWidth() && my >= rowY && my < rowY + ROW_H) {
            g.fill(getX(), rowY, getX() + getWidth(), rowY + ROW_H, 0x30FFFFFF);
        }
        boolean on = selected.contains(e.canonical());
        int boxX = getX() + 5;
        int boxY = rowY + 5;
        g.fill(boxX, boxY, boxX + 9, boxY + 9, on ? 0xFF2E7D32 : 0xFF808080);
        if (on) {
            g.fill(boxX + 2, boxY + 2, boxX + 7, boxY + 7, 0xFF7CFC7C);
        }
        int textX = boxX + 13;
        int right = getX() + getWidth() - GAP_X;
        // Distance + relative position sit right-aligned on the same row as the name.
        String meta = flat(tr(K + "rowmeta", flat(tr(e.dirKey())), e.distanceBlocks()));
        textRight(g, font, meta, right, rowY + 3, on ? 0xFFC8E6FF : 0xFF9A9A9A, S_SMALL);
        String line1 = flat(tr(K + "row1", label(index),
                flat(tr(e.doubleChest() ? K + "kind.double" : e.kindKey()))));
        int available = right - sw(font, meta, S_SMALL) - 4 - textX;
        text(g, font, clipScaled(font, line1, Math.max(8, available), S_BODY), textX, rowY + 3,
                on ? 0xFFFFFFFF : 0xFFD8D8D8, S_BODY);
        text(g, font, clipScaled(font, flat(tr(K + "row2", coordText(e.canonical()))),
                getWidth() - (textX - getX()) - GAP_X, S_SMALL), textX, rowY + 12, 0xFF909090, S_SMALL);
    }

    /** A..Z then AA.. for lists longer than 26, so a row keeps a stable human-readable name. */
    private static String label(int index) {
        int n = index;
        StringBuilder sb = new StringBuilder();
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static String coordText(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    /** Truncates with an ellipsis rather than letting text run past the panel edge. */
    private static String clip(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        String ellipsis = "…";
        int keep = s.length();
        while (keep > 0 && font.width(s.substring(0, keep) + ellipsis) > maxWidth) {
            keep--;
        }
        return s.substring(0, Math.max(0, keep)) + ellipsis;
    }

    /** Greedy wrap into at most {@code maxLines} lines; an ellipsis marks anything left over. */
    private static List<String> wrap(Font font, String s, int screenPx, float scale, int maxLines) {
        int maxFontPx = Math.max(4, (int) (screenPx / scale));
        List<String> lines = new ArrayList<>();
        String rest = s;
        while (!rest.isEmpty() && lines.size() < maxLines) {
            int cut = breakAt(font, rest, maxFontPx);
            if (cut >= rest.length()) {
                lines.add(rest.trim());
                rest = "";
            } else {
                lines.add(rest.substring(0, cut).trim());
                rest = rest.substring(cut).stripLeading();
            }
        }
        if (!rest.isEmpty() && !lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, clip(font, lines.get(last) + "…", maxFontPx));
        }
        return lines;
    }

    /** Break after the last space that still fits, else after the last glyph that fits. */
    private static int breakAt(Font font, String s, int maxFontPx) {
        int fit = 0;
        int lastSpace = -1;
        for (int i = 1; i <= s.length(); i++) {
            if (font.width(s.substring(0, i)) > maxFontPx) {
                break;
            }
            fit = i;
            if (s.charAt(i - 1) == ' ') {
                lastSpace = i;
            }
        }
        if (fit == 0) {
            return s.length();
        }
        return lastSpace > 0 ? lastSpace : fit;
    }

    /**
     * A disabled button keeps a readable label: it is dimmed, not blanked, so the user can still tell
     * which action is which and read the reason in the status line. The hover state is tested with the
     * same {@link #hit} the click uses, so brightening proves where the button actually is.
     */
    private void button(GuiGraphicsExtractor g, Font font, Rect r, String label, boolean active, int mx, int my) {
        boolean hovered = active && hit(r, mx, my);
        g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(),
                !active ? 0xFF242424 : hovered ? 0xFF545454 : 0xFF3A3A3A);
        g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + 1,
                !active ? 0xFF505050 : hovered ? 0xFFD0D0D0 : 0xFF9A9A9A);
        String shown = clipScaled(font, label, r.w() - 2 * (PAD_X - 1), S_BODY);
        text(g, font, shown, r.x() + (r.w() - sw(font, shown, S_BODY)) / 2, r.y() + (r.h() - 8) / 2,
                active ? 0xFFFFFFFF : 0xFF8A8A8A, S_BODY);
    }

    private String statusText() {
        if (holdingItem()) {
            return flat(tr(K + "hold_item"));
        }
        if (entries.isEmpty()) {
            // Without this, 全选/清空 on an empty list look dead: nothing changes and nothing says why.
            return flat(tr(K + "empty"));
        }
        if (selected.isEmpty()) {
            return flat(tr(K + "pick_first"));
        }
        if (selected.size() == 1 && previewValid) {
            return flat(tr(K + "one_selected"));
        }
        return flat(tr(K + (previewValid ? "ready" : "need_preview"), selected.size()));
    }

    private int statusColor() {
        if (holdingItem()) {
            return 0xFFFF5555;
        }
        if (selected.isEmpty()) {
            return 0xFF808080;
        }
        return previewValid ? 0xFF55FF55 : 0xFFFFAA00;
    }

    private boolean holdingItem() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && !mc.player.containerMenu.getCarried().isEmpty();
    }

    private boolean canAct() {
        return !busy && !selected.isEmpty() && !holdingItem();
    }

    // --- input -----------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (!isMouseOver(mx, my)) {
            return false;
        }
        Controls c = controls();
        String branch;
        if (hit(c.close(), mx, my)) {
            branch = "close";
            closeAction.run();
        } else if (hit(c.refresh(), mx, my)) {
            branch = "refresh";
            refresh();
        } else if (hit(c.selectAll(), mx, my)) {
            branch = "select_all";
            selectAllAvailable();
        } else if (hit(c.clear(), mx, my)) {
            branch = "clear";
            clearSelection();
        } else if (hit(c.preview(), mx, my)) {
            branch = "preview";
            requestPreview();
        } else if (hit(c.commit(), mx, my)) {
            branch = "commit";
            requestCommit();
        } else {
            branch = "row";
            toggleRowAt(my);
        }
        // Hit rectangles are logged only under the config debug flag; the ui.geom dump already proves
        // the panel fits its window, which is the invariant that matters for shipping.
        Diag.debug("ui.click mx=" + mx + " my=" + my + " branch=" + branch
                + " panel=" + getX() + "," + getY() + " " + getWidth() + "x" + getHeight()
                + " refresh=" + rect(c.refresh()) + " close=" + rect(c.close())
                + " selectAll=" + rect(c.selectAll()) + " clear=" + rect(c.clear())
                + " preview=" + rect(c.preview()) + " commit=" + rect(c.commit())
                + " entries=" + entries.size() + " selected=" + selected.size());
        // Consume unconditionally: a click in the panel must never also reach an inventory slot.
        return true;
    }

    private static String rect(Rect r) {
        return r.x() + "," + r.y() + " " + r.w() + "x" + r.h();
    }

    /** Two pixels of slop on every side: the drawn button is what is clickable, plus a hair. */
    private static boolean hit(Rect r, double mx, double my) {
        int pad = 2;
        return mx >= r.x() - pad && mx < r.x() + r.w() + pad && my >= r.y() - pad && my < r.y() + r.h() + pad;
    }

    private void toggleRowAt(double my) {
        if (my < listTop() || my >= listBottom()) {
            return;
        }
        int index = scroll + (int) ((my - listTop()) / ROW_H);
        if (index < 0 || index >= entries.size()) {
            return;
        }
        BlockPos key = entries.get(index).canonical();
        if (!selected.remove(key)) {
            selected.add(key);
        }
        invalidatePreview();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (!isMouseOver(mx, my)) {
            return false;
        }
        int max = Math.max(0, entries.size() - visibleRows);
        scroll = (int) Math.min(max, Math.max(0, scroll - Math.signum(scrollY)));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
    }

    // --- requests --------------------------------------------------------------------------

    private void requestPreview() {
        if (!canAct()) {
            return;
        }
        busy = true;
        planId = UUID.randomUUID();
        Diag.phase(planId, "client_request", "preview=true candidates=" + selected.size());
        send(planId, true);
    }

    private void requestCommit() {
        if (!canAct() || !previewValid || planId == null) {
            return;
        }
        busy = true;
        Diag.phase(planId, "client_confirm", "candidates=" + selected.size());
        send(planId, false);
    }

    private void send(UUID id, boolean preview) {
        Minecraft mc = Minecraft.getInstance();
        int radius = CasConfig.server().radius.get();
        ClientPacketDistributor.sendToServer(new SortRequestPayload(
                id, mc.player.tickCount, preview, List.copyOf(selected), radius));
    }

    /** Called from the client payload handler with the server's verdict. */
    void onResult(UUID requestId, boolean preview, boolean ok, List<String> lines) {
        if (planId != null && !planId.equals(requestId)) {
            return; // stale or reordered reply: never mutate the current plan
        }
        busy = false;
        previewValid = preview && ok;
        statusLines.clear();
        statusLines.addAll(lines);
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String kindOf(BlockState state) {
        return state.getBlock() instanceof TrappedChestBlock
                ? K + "kind.trapped" : K + "kind.chest";
    }

    /**
     * Direction relative to where the player is looking, because an absolute compass face reads as
     * nonsense in a panel that is about "what is in front of me".
     */
    private static String relativeDir(Player player, Vec3 target) {
        Vec3 look = player.getLookAngle();
        double len = Math.hypot(look.x, look.z);
        if (len < 1.0E-4) {
            return K + "dir.unknown";
        }
        double fx = look.x / len;
        double fz = look.z / len;
        // Facing north (0,-1) puts east (+1,0) on the right hand.
        double rx = -fz;
        double rz = fx;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double dl = Math.hypot(dx, dz);
        if (dl < 1.0E-4) {
            return K + "dir.ahead";
        }
        dx /= dl;
        dz /= dl;
        double ahead = dx * fx + dz * fz;
        double right = dx * rx + dz * rz;
        double angle = Math.toDegrees(Math.atan2(right, ahead));
        if (angle >= -45 && angle < 45) {
            return K + "dir.ahead";
        } else if (angle >= 45 && angle < 135) {
            return K + "dir.right";
        } else if (angle >= -135 && angle < -45) {
            return K + "dir.left";
        }
        return K + "dir.behind";
    }

    private static BlockPos canonicalAnchor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.RIGHT) {
            return pos;
        }
        return pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
    }

    private static AABB bounds(Level level, BlockPos pos) {
        AABB box = new AABB(pos);
        BlockState state = level.getBlockState(pos);
        if (state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            box = box.minmax(new AABB(other));
        }
        return box;
    }
}
