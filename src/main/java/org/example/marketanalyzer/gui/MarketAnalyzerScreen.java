package org.example.marketanalyzer.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.example.marketanalyzer.data.MarketDataStore;
import org.example.marketanalyzer.data.MarketItem;
import org.example.marketanalyzer.data.PricePoint;
import org.example.marketanalyzer.data.TrackedItemsConfig;
import org.example.marketanalyzer.logic.AutoScanScheduler;
import org.example.marketanalyzer.logic.MarketAutomator;
import org.example.marketanalyzer.logic.MarketScanner;

import java.text.SimpleDateFormat;
import java.util.*;

public class MarketAnalyzerScreen extends Screen {

    private final Screen parent;

    // Panel — FIXED size, centered, fully opaque
    private static final int W = 420;
    private static final int H = 290;
    private int px, py;

    private int tab = 0; // 0 = Watch List, 1 = Chart, 2 = Deals, 3 = Config
    private TextFieldWidget apiKeyField;

    // Watch list
    private String newItemInput = "";
    private boolean inputFocused = false;
    private int watchScroll = 0;

    // Chart
    private String selectedItem = null;

    // ── Autocomplete state ────────────────────────────────────
    private static final int MAX_SUGGESTIONS = 8;
    private List<String> suggestions = new ArrayList<>();
    private int suggestionScroll = 0;
    private int hoveredSuggestion = -1;
    /** All registered Minecraft item names — built once on first use */
    private static List<String> allItemNames = null;

    private static List<String> getAllItemNames() {
        if (allItemNames == null) {
            allItemNames = new ArrayList<>();
            net.minecraft.registry.Registries.ITEM.forEach(item -> {
                try {
                    net.minecraft.util.Identifier id = net.minecraft.registry.Registries.ITEM.getId(item);
                    if (id == null) return;
                    String path = id.getPath();
                    String[] words = path.split("_");
                    StringBuilder sb = new StringBuilder();
                    for (String w : words) {
                        if (!w.isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(Character.toUpperCase(w.charAt(0)));
                            sb.append(w.substring(1));
                        }
                    }
                    String display = sb.toString();
                    if (!display.isBlank()) allItemNames.add(display + "|" + id.toString());
                } catch (Exception ignored) {}
            });
            allItemNames.sort(String::compareToIgnoreCase);
        }
        return allItemNames;
    }

    private void updateSuggestions() {
        suggestions.clear();
        suggestionScroll = 0;
        hoveredSuggestion = -1;
        if (newItemInput.isBlank()) return;
        String q = newItemInput.toLowerCase().trim();

        for (String entry : getAllItemNames()) {
            String name = entry.split("\\|")[0];
            String lower = name.toLowerCase();
            if (lower.contains(q)) {
                suggestions.add(entry);
            }
            if (suggestions.size() >= 40) break;
        }
    }


    // ── Modern Palette (Premium Dark) ──────────────
    private static final int BG        = 0xDD05080F; // deep black translucent
    private static final int TOPBAR_S  = 0xFF1A2540; // gradient start
    private static final int PANEL2    = 0x880E1620; // semi-translucent sub-panel
    private static final int BORDER    = 0x443A78FF; // subtle blue border
    private static final int ACCENT    = 0xFF3A78FF; // electric blue
    private static final int CYAN_COL  = 0xFF00D4FF; // highlights
    private static final int WHITE_COL = 0xFFF0F4FF; // crisp white
    private static final int GRAY_COL  = 0xFF8090B0; // muted text
    private static final int LTGRAY    = 0xFFB0C0E0; // light gray
    private static final int GREEN_COL = 0xFF10B981; // success
    private static final int RED_COL   = 0xFFFF3355; // danger
    private static final int SEL       = 0x443A78FF; // selected highlight
    private static final int HOV       = 0x22FFFFFF; // hover highlight
    
    // Legacy buttons for compatibility
    private static final int BTN_SCAN  = 0xFF1A3A6E;
    private static final int BTN_ADD   = 0xFF0F3A20;
    private static final int BTN_DEL   = 0xFF3A0F18;
    private static final int BTN_CHT   = 0xFF0F1E40;

    public MarketAnalyzerScreen(Screen parent) {
        super(Text.literal("Market Analyzer"));
        this.parent = parent;
    }

    private boolean showTutorial = false;

    @Override
    protected void init() {
        px = (width - W) / 2;
        py = (height - H) / 2;

        // [X] close — top right
        addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> {
            assert client != null;
            client.setScreen(parent);
        }).dimensions(px + W - 20, py + 6, 14, 14).build());

        // [WEB] Open Dashboard
        addDrawableChild(ButtonWidget.builder(Text.literal("🔗 Dashboard"), b -> {
            net.minecraft.util.Util.getOperatingSystem().open(TrackedItemsConfig.getApiUrl());
        }).dimensions(px + W - 105, py + 6, 80, 14).build());

        // [?] Tutorial
        addDrawableChild(ButtonWidget.builder(Text.literal("?"), b -> {
            showTutorial = !showTutorial;
        }).dimensions(px + W - 122, py + 6, 14, 14).build());

        // API Key Field for Config Tab
        apiKeyField = new TextFieldWidget(textRenderer, px + 144, py + 158, 240, 16, Text.literal("API Key"));
        apiKeyField.setMaxLength(64);
        apiKeyField.setText(TrackedItemsConfig.getApiKey());
        apiKeyField.setRenderTextProvider((str, firstCharacterIndex) -> net.minecraft.text.OrderedText.styledForwardsVisitedString(str.replaceAll(".", "*"), net.minecraft.text.Style.EMPTY));
        apiKeyField.setChangedListener(text -> TrackedItemsConfig.setApiKey(text));
        apiKeyField.setVisible(tab == 3);
        addDrawableChild(apiKeyField);
    }

    // ── RENDER ───────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Subtle outer glow
        fillBorder(ctx, px - 1, py - 1, W + 2, H + 2, 1, 0x333A78FF);

        // Main glass panel
        ctx.fill(px, py, px + W, py + H, BG);
        fillBorder(ctx, px, py, W, H, 1, BORDER);

        // Top bar
        ctx.fill(px + 1, py + 1, px + W - 1, py + 24, TOPBAR_S);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§b⬡ §fMarket Analyzer"), px + 10, py + 8, WHITE_COL);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8| §7v2.0 Premium"), px + 105, py + 8, GRAY_COL);
        ctx.fill(px + 1, py + 23, px + W - 1, py + 24, BORDER);

        // Tabs
        drawTabs(ctx, mouseX, mouseY);
        ctx.fill(px + 8, py + 45, px + W - 8, py + 46, 0x22FFFFFF);

        // Content area
        if (tab == 0)      renderWatchList(ctx, mouseX, mouseY);
        else if (tab == 1) renderChart(ctx, mouseX, mouseY);
        else if (tab == 2) renderDeals(ctx, mouseX, mouseY);
        else               renderSettings(ctx, mouseX, mouseY);

        // Update API Key field visibility based on tab
        if (apiKeyField != null) apiKeyField.setVisible(tab == 3 && !showTutorial);

        // Status bar
        renderStatusBar(ctx);

        super.render(ctx, mouseX, mouseY, delta);

        if (showTutorial) renderTutorial(ctx);
    }

    private void renderStatusBar(DrawContext ctx) {
        int sbY = py + H - 32;
        ctx.fill(px + 8, sbY, px + W - 8, py + H - 8, 0x33000000);
        fillBorder(ctx, px + 8, sbY, W - 16, 24, 1, 0x22FFFFFF);

        if (MarketScanner.scanInProgress) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("§e⚡ §fScanning: §b" + MarketScanner.lastScannedItem), px + 15, sbY + 7, WHITE_COL);
        } else {
            int n = MarketDataStore.getAllData().size();
            ctx.drawTextWithShadow(textRenderer, Text.literal(n > 0 ? "§a✔ §7Network: §fStable" : "§7● No data"), px + 15, sbY + 7, WHITE_COL);
        }

        if (AutoScanScheduler.isEnabled()) {
            String timer = MarketScanner.scanInProgress ? "§aActive" : "§b" + AutoScanScheduler.getCountdownString();
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Next Scan: " + timer), px + W - 110, sbY + 7, GRAY_COL);
        }
    }

    private void renderTutorial(DrawContext ctx) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Dim the entire screen background to focus on tutorial
        ctx.fill(0, 0, width, height, 0xAA000000);

        int tx = px + 40, ty = py + 60, tw = W - 80, th = H - 100;
        
        // Outer glow/border
        fillBorder(ctx, tx - 1, ty - 1, tw + 2, th + 2, 1, ACCENT);
        // Solid dark background for the box (Fully Opaque)
        ctx.fill(tx, ty, tx + tw, ty + th, 0xFF05080F);
        
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§b§lQuick Tutorial & Tips"), tx + tw / 2, ty + 12, WHITE_COL);
        ctx.fill(tx + 20, ty + 25, tx + tw - 20, ty + 26, 0x22FFFFFF);
        
        String[] steps = {
            "§e1. Watch List: §fType an item name and click 'Add'.",
            "§e2. Manual Scan: §fClick the §b▶§f button to update prices.",
            "§e3. Auto-Scan: §fEnable in §bCONFIG§f to track prices while playing.",
            "§e4. Hot Deals: §fItems at -10% average price appear in §6DEALS§f.",
            "§e5. Dashboard: §fClick the button above for cloud statistics!",
            "§e6. Auto-Sell: §fPress §bSuggest & Sell§f in Charts to sell assets."
        };
        
        for (int i = 0; i < steps.length; i++) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(steps[i]), tx + 15, ty + 40 + (i * 18), WHITE_COL);
        }
        
        ctx.fill(tx + 20, ty + th - 25, tx + tw - 20, ty + th - 24, 0x22FFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§7Click [?] to close this help window"), tx + tw / 2, ty + th - 16, GRAY_COL);
    }

    // ── TABS ─────────────────────────────────────────────────
    private void drawTabs(DrawContext ctx, int mx, int my) {
        // Count deals for badge
        int dealCount = 0;
        for (String name : TrackedItemsConfig.getItems()) {
            MarketItem item = MarketDataStore.getItem(name);
            if (item == null || item.getHistory().isEmpty()) continue;
            double last = item.getHistory().get(item.getHistory().size()-1).getPrice();
            double avg = item.getAverageRecentPrice(7L * 24 * 3600000);
            if (last > 0 && last < avg * 0.9) dealCount++;
        }
        
        String[] labels = { "WATCH", "CHARTS", "DEALS" + (dealCount > 0 ? " §c●" : ""), "CONFIG" };
        String[] rawLabels = { "WATCH", "CHARTS", "DEALS", "CONFIG" };
        int tx = px + 12, ty = py + 28;
        int h = 14, spacing = 6;
        int cur = tx;

        for (int i = 0; i < labels.length; i++) {
            int tw = textRenderer.getWidth(rawLabels[i]) + 16;
            if (dealCount > 0 && i == 2) tw += 10; // space for badge
            boolean sel = (tab == i);
            boolean hov = mx >= cur && mx < cur + tw && my >= ty && my < ty + h;
            
            if (sel) {
                ctx.fill(cur, ty, cur + tw, ty + h, ACCENT);
            } else if (hov) {
                ctx.fill(cur, ty, cur + tw, ty + h, 0x44FFFFFF);
            } else {
                ctx.fill(cur, ty, cur + tw, ty + h, 0x11FFFFFF);
            }

            int textColor = sel ? WHITE_COL : hov ? WHITE_COL : GRAY_COL;
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(labels[i]), cur + tw / 2, ty + 3, textColor);
            cur += tw + spacing;
        }
    }

    // ── WATCH LIST TAB ────────────────────────────────────────
    private void renderWatchList(DrawContext ctx, int mx, int my) {
        int x = px + 6, y = py + 48;
        int iw = W - 12; // input row width
        int fieldW = iw - 100; // MUST match mouseClicked

        // ── Row 1: Input + buttons ──
        ctx.fill(x, y, x + fieldW, y + 18, 0x44000000);
        fillBorder(ctx, x, y, fieldW, 18, 1, inputFocused ? ACCENT : 0x22FFFFFF);
        String disp = newItemInput.isEmpty()
            ? "§8Enter item name..."
            : "§f" + newItemInput + (inputFocused ? "§b|" : "");
        ctx.drawTextWithShadow(textRenderer, Text.literal(disp), x + 6, y + 5, WHITE_COL);

        // ── Autocomplete dropdown (drawn OVER everything else, below input) ──
        if (inputFocused && !suggestions.isEmpty()) {
            int dropY = y + 18;
            int dropH = Math.min(suggestions.size(), MAX_SUGGESTIONS) * 13 + 2;
            int dropW = fieldW;
            // Shadow
            ctx.fill(x + 2, dropY + 2, x + dropW + 2, dropY + dropH + 2, 0x88000000);
            // Panel
            ctx.fill(x, dropY, x + dropW, dropY + dropH, 0xFF0B1220);
            fillBorder(ctx, x, dropY, dropW, dropH, 1, ACCENT);

            int vis = Math.min(suggestions.size() - suggestionScroll, MAX_SUGGESTIONS);
            for (int si = 0; si < vis; si++) {
                int sidx = suggestionScroll + si;
                String entry = suggestions.get(sidx);
                String name = entry.split("\\|")[0];
                int sy = dropY + 1 + si * 13;
                boolean sHov = (sidx == hoveredSuggestion) ||
                    (mx >= x && mx < x + dropW && my >= sy && my < sy + 13);
                if (sHov) hoveredSuggestion = sidx;
                ctx.fill(x + 1, sy, x + dropW - 1, sy + 13,
                    sHov ? SEL : (si % 2 == 0 ? 0xFF0E1828 : 0xFF0B1220));
                
                String q = newItemInput.toLowerCase();
                int matchIdx = name.toLowerCase().indexOf(q);
                if (matchIdx >= 0) {
                    String before = name.substring(0, matchIdx);
                    String match  = name.substring(matchIdx, matchIdx + q.length());
                    String after  = name.substring(matchIdx + q.length());
                    int bw = textRenderer.getWidth(before);
                    int mw = textRenderer.getWidth(match);
                    ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + before), x + 4, sy + 2, GRAY_COL);
                    ctx.drawTextWithShadow(textRenderer, Text.literal("§f" + match), x + 4 + bw, sy + 2, WHITE_COL);
                    ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + after), x + 4 + bw + mw, sy + 2, LTGRAY);
                } else {
                    ctx.drawTextWithShadow(textRenderer, Text.literal("§7" + name), x + 4, sy + 2, LTGRAY);
                }
            }
            // Scrollbar if needed
            if (suggestions.size() > MAX_SUGGESTIONS) {
                int sbH = dropH - 2;
                int thumbH = Math.max(8, sbH * MAX_SUGGESTIONS / suggestions.size());
                int thumbY = dropY + 1 + (sbH - thumbH) * suggestionScroll / Math.max(1, suggestions.size() - MAX_SUGGESTIONS);
                ctx.fill(x + dropW - 4, dropY + 1, x + dropW - 1, dropY + dropH - 1, 0x22FFFFFF);
                ctx.fill(x + dropW - 4, thumbY, x + dropW - 1, thumbY + thumbH, ACCENT);
            }
        }

        // [+] Add
        int addX = x + fieldW + 4;
        boolean addH = mx >= addX && mx < addX + 44 && my >= y && my < y + 18;
        ctx.fill(addX, y, addX + 44, y + 18, addH ? 0xFF10B981 : 0xFF065F46);
        fillBorder(ctx, addX, y, 44, 18, 1, GREEN_COL);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§a+ ADD"), addX + 22, y + 5, WHITE_COL);

        // [▶ Scan]
        int scanX = addX + 48;
        boolean sc = MarketScanner.scanInProgress;
        boolean scanH = !sc && mx >= scanX && mx < scanX + 44 && my >= y && my < y + 18;
        ctx.fill(scanX, y, scanX + 44, y + 18, sc ? 0xFF1E293B : scanH ? 0xFF1D4ED8 : 0xFF1E40AF);
        fillBorder(ctx, scanX, y, 44, 18, 1, sc ? GRAY_COL : ACCENT);
        ctx.drawCenteredTextWithShadow(textRenderer,
            Text.literal(sc ? "§7SCAN" : "§b▶ SCAN"), scanX + 22, y + 5, sc ? GRAY_COL : WHITE_COL);

        // ── Item list ──
        List<String> tracked = TrackedItemsConfig.getItems();
        int listY = y + 24;
        int lineH = 18;
        int maxV = (py + H - listY - 36) / lineH;

        // Header
        ctx.fill(x, listY, x + iw, listY + 14, 0x22FFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Asset Name"), x + 18, listY + 3, GRAY_COL);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Last Price"), x + iw - 130, listY + 3, GRAY_COL);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Actions"), x + iw - 55, listY + 3, GRAY_COL);
        listY += 16;

        if (tracked.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7No items tracked. Add one above and click §b▶ SCAN"),
                px + W / 2, listY + 28, GRAY_COL);
        }

        for (int i = watchScroll; i < Math.min(tracked.size(), watchScroll + maxV); i++) {
            String name = tracked.get(i);
            int iy = listY + (i - watchScroll) * lineH;
            boolean hov = mx >= x && mx < x + iw && my >= iy && my < iy + lineH;
            ctx.fill(x, iy, x + iw, iy + lineH - 1, hov ? HOV : (i % 2 == 0 ? 0xFF0E1520 : BG));

            // Trend arrow
            MarketItem item = MarketDataStore.getItem(name);
            String trend = "—"; int tc = GRAY_COL;
            if (item != null && item.getHistory().size() >= 2) {
                List<PricePoint> h = item.getHistory();
                double last = h.get(h.size()-1).getPrice(), prev = h.get(h.size()-2).getPrice();
                if (last > prev)      { trend = "▲"; tc = GREEN_COL; }
                else if (last < prev) { trend = "▼"; tc = RED_COL; }
                else                  { trend = "─"; tc = GRAY_COL; }
            }
            ctx.drawTextWithShadow(textRenderer, Text.literal(trend), x + 2, iy + 5, tc);

            // Name
            String display = name.length() > 22 ? name.substring(0, 20) + "…" : name;
            ctx.drawTextWithShadow(textRenderer, Text.literal("§f" + display), x + 14, iy + 5, WHITE_COL);

            // Latest price
            if (item != null && !item.getHistory().isEmpty()) {
                double lp = item.getHistory().get(item.getHistory().size()-1).getPrice();
                ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§e$" + MarketScanner.fmt(lp)), x + iw - 130, iy + 5, WHITE_COL);
            }

            // [↗ Chart] button
            int cX = x + iw - 56, cY = iy + 2;
            boolean cH = mx >= cX && mx < cX + 28 && my >= cY && my < cY + 14;
            ctx.fill(cX, cY, cX + 28, cY + 14, cH ? 0xFF102060 : BTN_CHT);
            fillBorder(ctx, cX, cY, 28, 14, 1, ACCENT);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§9Chart"), cX + 14, cY + 3, WHITE_COL);

            // [✕] Remove button
            int rX = cX + 32, rY = cY;
            boolean rH = mx >= rX && mx < rX + 18 && my >= rY && my < rY + 14;
            ctx.fill(rX, rY, rX + 18, rY + 14, rH ? 0xFF601020 : BTN_DEL);
            fillBorder(ctx, rX, rY, 18, 14, 1, RED_COL);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§c✕"), rX + 9, rY + 3, WHITE_COL);
        }
    }

    // ── CHART TAB ─────────────────────────────────────────────
    private void renderChart(DrawContext ctx, int mx, int my) {
        int x = px + 6, y = py + 48;
        int aw = W - 12;

        if (selectedItem == null) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Select an asset from the §bWATCH§7 list to view charts"),
                px + W / 2, py + H / 2 - 10, GRAY_COL);
            return;
        }

        MarketItem item = MarketDataStore.getItem(selectedItem);
        if (item == null || item.getHistory().isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7No data samples for §f" + selectedItem + "§7 — click §bSCAN"),
                px + W / 2, py + H / 2, GRAY_COL);
            return;
        }

        // Header row
        ctx.drawTextWithShadow(textRenderer, Text.literal("§b" + selectedItem), x, y + 2, WHITE_COL);
        double avg = item.getAverageRecentPrice(7L * 24 * 3600000);
        double low = item.getLowestRecentPrice(24L * 3600000);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§87d Avg: §e$" + MarketScanner.fmt(avg)), x + 160, y + 2, GRAY_COL);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§824h Min: §a$" + MarketScanner.fmt(low)), x + 260, y + 2, GRAY_COL);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§8Samples: §7" + item.getHistory().size()), x, y + 12, GRAY_COL);

        // Graph
        List<PricePoint> hist = item.getHistory();
        List<PricePoint> pts = hist.size() > 80 ? hist.subList(hist.size()-80, hist.size()) : hist;
        int gx = x, gy = y + 26, gw = aw, gh = H - 114;
        if (pts.size() >= 2) renderGraph(ctx, pts, gx, gy, gw, gh, mx, my);
        else ctx.drawCenteredTextWithShadow(textRenderer,
            Text.literal("§7Potrzebne ≥2 punkty danych"), gx + gw/2, gy + gh/2, GRAY_COL);

        // Znajdź ilość w ekwipunku
        int invCount = 0;
        if (client != null && client.player != null) {
            for (int i = 0; i < client.player.getInventory().size(); i++) {
                net.minecraft.item.ItemStack stack = client.player.getInventory().getStack(i);
                if (!stack.isEmpty()) {
                    String name = stack.getName().getString();
                    if (name.equals(selectedItem)) {
                        invCount += stack.getCount();
                    }
                }
            }
        }

        // Auto-sell bar
        int bY = py + H - 54;
        boolean bH = mx >= x && mx < x + aw && my >= bY && my < bY + 18;
        ctx.fill(x, bY, x + aw, bY + 18, bH ? 0xFF10B981 : 0xFF065F46);
        fillBorder(ctx, x, bY, aw, 18, 1, GREEN_COL);
        ctx.drawCenteredTextWithShadow(textRenderer,
            Text.literal("§a▲ AUTO-SELL §8| §7Suggest: §e$" + MarketScanner.fmt(low > 1 ? low - 1 : low) + " §8| §fOwned: " + invCount),
            x + aw/2, bY + 5, WHITE_COL);
    }

    // ── DEALS TAB (HOT DEALS) ─────────────────────────────────
    private void renderDeals(DrawContext ctx, int mx, int my) {
        int x = px + 10, y = py + 54;
        List<String> tracked = TrackedItemsConfig.getItems();
        List<String> deals = new ArrayList<>();
        
        for (String name : tracked) {
            MarketItem item = MarketDataStore.getItem(name);
            if (item == null || item.getHistory().isEmpty()) continue;
            double last = item.getHistory().get(item.getHistory().size()-1).getPrice();
            double avg = item.getAverageRecentPrice(7L * 24 * 3600000);
            if (last > 0 && last < avg * 0.9) deals.add(name);
        }

        if (deals.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§7No hot deals detected right now..."), px + W/2, py + H/2, GRAY_COL);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§8(Monitoring assets for -10% price dips)"), px + W/2, py + H/2 + 12, GRAY_COL);
            return;
        }

        ctx.drawTextWithShadow(textRenderer, Text.literal("§6§l🔥 HOT DEALS"), x, y - 6, WHITE_COL);
        int dy = y + 10;
        for (String name : deals) {
            MarketItem item = MarketDataStore.getItem(name);
            double last = item.getHistory().get(item.getHistory().size()-1).getPrice();
            double avg = item.getAverageRecentPrice(7L * 24 * 3600000);
            double save = ((avg - last) / avg) * 100;

            ctx.fill(x, dy, x + W - 20, dy + 18, 0x2210B981);
            fillBorder(ctx, x, dy, W - 20, 18, 1, 0x4410B981);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§f" + name), x + 6, dy + 5, WHITE_COL);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§a-" + String.format("%.0f", save) + "% OFF"), x + 150, dy + 5, GREEN_COL);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§e$" + MarketScanner.fmt(last)), x + W - 80, dy + 5, WHITE_COL);
            dy += 22;
            if (dy > py + H - 40) break;
        }
    }

    // ── SETTINGS TAB (CONFIG) ─────────────────────────────────
    private void renderSettings(DrawContext ctx, int mx, int my) {
        int sx = px + 14, sy = py + 54;
        ctx.drawTextWithShadow(textRenderer, Text.literal("§b§lSystem Settings"), sx, sy - 6, WHITE_COL);
        sy += 18;

        // Auto-Scan Toggle
        ctx.drawTextWithShadow(textRenderer, Text.literal("§fBackground Auto-Scan"), sx, sy + 4, WHITE_COL);
        boolean auto = AutoScanScheduler.isEnabled();
        int togX = sx + 130, togW = 70;
        boolean hov = mx >= togX && mx < togX + togW && my >= sy && my < sy + 16;
        ctx.fill(togX, sy, togX + togW, sy + 16, auto ? 0xFF10B981 : 0xFF334155);
        fillBorder(ctx, togX, sy, togW, 16, 1, auto ? GREEN_COL : GRAY_COL);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(auto ? "ENABLED" : "DISABLED"), togX + togW/2, sy + 4, WHITE_COL);

        sy += 24;
        // Interval
        ctx.drawTextWithShadow(textRenderer, Text.literal("§fScan Interval (min)"), sx, sy + 4, WHITE_COL);
        int[] intervals = {1, 2, 5, 10};
        int curX = sx + 130;
        for (int iv : intervals) {
            boolean sel = (AutoScanScheduler.getIntervalMinutes() == iv);
            boolean bH = mx >= curX && mx < curX + 32 && my >= sy && my < sy + 16;
            ctx.fill(curX, sy, curX + 32, sy + 16, sel ? ACCENT : bH ? 0x44FFFFFF : 0x11FFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(iv + "m"), curX + 16, sy + 4, sel ? WHITE_COL : GRAY_COL);
            curX += 36;
        }

        sy += 24;
        // Snipe Threshold
        ctx.drawTextWithShadow(textRenderer, Text.literal("§fSnipe Threshold (%)"), sx, sy + 4, WHITE_COL);
        int[] thresholds = {10, 20, 30, 50};
        curX = sx + 130;
        for (int th : thresholds) {
            boolean sel = (AutoScanScheduler.getSnipeThreshold() == th);
            boolean bH = mx >= curX && mx < curX + 32 && my >= sy && my < sy + 16;
            ctx.fill(curX, sy, curX + 32, sy + 16, sel ? ACCENT : bH ? 0x44FFFFFF : 0x11FFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(th + "%"), curX + 16, sy + 4, sel ? WHITE_COL : GRAY_COL);
            curX += 36;
        }

        sy += 40;
        // Countdown display in Config
        if (auto) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§7Next automatic scan in: §b" + AutoScanScheduler.getCountdownString()), px + W/2, sy, GRAY_COL);
            sy += 15;
        }

        // API Key
        ctx.drawTextWithShadow(textRenderer, Text.literal("§fAPI Secret Key"), sx, sy + 4, WHITE_COL);
        // Field is rendered by the widget system automatically
        sy += 24;

        // Export CSV
        int expW = 160, expX = px + W/2 - expW/2;
        boolean eH = mx >= expX && mx < expX + expW && my >= sy && my < sy + 18;
        ctx.fill(expX, sy, expX + expW, sy + 18, eH ? 0x44FFFFFF : 0x11FFFFFF);
        fillBorder(ctx, expX, sy, expW, 18, 1, GRAY_COL);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("📊 Export Data to CSV"), expX + expW/2, sy + 5, WHITE_COL);
    }

    private void renderGraph(DrawContext ctx, List<PricePoint> pts, int gx, int gy, int gw, int gh, int mx, int my) {
        if (pts.size() < 2) return;
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (PricePoint p : pts) {
            if (p.getPrice() < min) min = p.getPrice();
            if (p.getPrice() > max) max = p.getPrice();
        }
        if (max == min) { min -= 1; max += 1; }
        double range = max - min;
        
        // Avg line
        double avg = pts.stream().mapToDouble(PricePoint::getPrice).average().orElse(0);
        int avgY = gy + gh - (int) ((avg - min) * gh / range);
        // Dashed average line
        for (int dx = 0; dx < gw; dx += 6) {
            ctx.fill(gx + dx, avgY, gx + Math.min(dx + 4, gw), avgY + 1, 0x88F59E0B);
        }
        ctx.drawTextWithShadow(textRenderer, Text.literal("§6avg"), gx + gw - 24, avgY - 8, 0xFFF59E0B);
        
        // Grid lines
        for (int i = 0; i <= 4; i++) {
            int ly = gy + gh - (i * gh / 4);
            ctx.fill(gx, ly, gx + gw, ly + 1, 0x11FFFFFF);
            double val = min + (i * range / 4);
            ctx.drawTextWithShadow(textRenderer, Text.literal("$" + MarketScanner.fmt(val)), gx - 38, ly - 4, GRAY_COL);
        }
        
        // Gradient fill under curve (simplified)
        for (int i = 0; i < pts.size() - 1; i++) {
            int x1 = gx + (i * gw / (pts.size() - 1));
            int y1 = gy + gh - (int) ((pts.get(i).getPrice() - min) * gh / range);
            int x2 = gx + ((i + 1) * gw / (pts.size() - 1));
            int y2 = gy + gh - (int) ((pts.get(i + 1).getPrice() - min) * gh / range);
            // Line
            ctx.fill(x1, Math.min(y1, y2), x2, Math.max(y1, y2) + 1, CYAN_COL);
            // Light fill under
            ctx.fill(x1, Math.min(y1, y2), x2, gy + gh, 0x11009EFF);
        }
        
        // Hover tooltip
        for (int i = 0; i < pts.size(); i++) {
            int hx = gx + (i * gw / (pts.size() - 1));
            int hy = gy + gh - (int) ((pts.get(i).getPrice() - min) * gh / range);
            if (mx >= hx - 3 && mx <= hx + 3 && my >= gy && my <= gy + gh) {
                // Vertical crosshair line
                ctx.fill(hx, gy, hx + 1, gy + gh, 0x44FFFFFF);
                // Dot on line
                ctx.fill(hx - 3, hy - 3, hx + 4, hy + 4, 0xFF05080F);
                ctx.fill(hx - 2, hy - 2, hx + 3, hy + 3, CYAN_COL);
                // Tooltip
                PricePoint p = pts.get(i);
                String priceLine = "$" + String.format("%.2f", p.getPrice());
                String dateLine = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(p.getTimestamp()));
                double change = avg > 0 ? ((p.getPrice() - avg) / avg * 100) : 0;
                String changeLine = (change >= 0 ? "§a+" : "§c") + String.format("%.1f%%", change);
                int tipW = Math.max(textRenderer.getWidth(priceLine), textRenderer.getWidth(dateLine)) + 16;
                int tipX = hx + 10, tipY = hy - 36;
                if (tipX + tipW > gx + gw) tipX = hx - tipW - 10;
                if (tipY < gy) tipY = hy + 6;
                // Tooltip box
                ctx.fill(tipX - 1, tipY - 1, tipX + tipW + 1, tipY + 39, BORDER);
                ctx.fill(tipX, tipY, tipX + tipW, tipY + 38, 0xFF05080F);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§f" + priceLine), tipX + 6, tipY + 5, WHITE_COL);
                ctx.drawTextWithShadow(textRenderer, Text.literal(changeLine), tipX + 6, tipY + 16, WHITE_COL);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8" + dateLine), tipX + 6, tipY + 27, GRAY_COL);
            }
        }
    }

    // ── HELPERS ──────────────────────────────────────────────
    private void fillBorder(DrawContext ctx, int x, int y, int w, int h, int t, int color) {
        ctx.fill(x, y, x + w, y + t, color);
        ctx.fill(x, y + h - t, x + w, y + h, color);
        ctx.fill(x, y, x + t, y + h, color);
        ctx.fill(x + w - t, y, x + w, y + h, color);
    }

    // ── INPUT ─────────────────────────────────────────────────
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int x = px + 6, y = py + 48;
        int iw = W - 12;

        // Tabs handling
        int tx = px + 12, ty = py + 28, th = 14;
        int curTabX = tx;
        for (int i = 0; i < 4; i++) {
            String[] labels = { "WATCH", "CHARTS", "DEALS", "CONFIG" };
            int tw = textRenderer.getWidth(labels[i]) + 16;
            if (mx >= curTabX && mx < curTabX + tw && my >= ty && my < ty + th) { tab = i; return true; }
            curTabX += tw + 6;
        }

        if (tab == 0) {
            int fieldW = iw - 90;
            // Autocomplete suggestions
            if (inputFocused && !suggestions.isEmpty()) {
                int dropY = y + 18;
                int vis = Math.min(suggestions.size() - suggestionScroll, MAX_SUGGESTIONS);
                for (int si = 0; si < vis; si++) {
                    int sy = dropY + 1 + si * 13;
                    if (mx >= x && mx < x + fieldW && my >= sy && my < sy + 13) {
                        String entry = suggestions.get(suggestionScroll + si);
                        // Use friendly name (part before |) instead of full technical ID
                        newItemInput = entry.split("\\|")[0];
                        suggestions.clear(); hoveredSuggestion = -1; return true;
                    }
                }
            }
            // Input focus
            if (mx >= x && mx < x + fieldW && my >= y && my < y + 18) { inputFocused = true; updateSuggestions(); return true; }
            if (!suggestions.isEmpty() && mx >= x && mx < x + fieldW && my >= y + 18) return true;
            inputFocused = false; suggestions.clear();

            // Add item
            int addX = x + fieldW + 4;
            if (mx >= addX && mx < addX + 38 && my >= y && my < y + 18) {
                if (!newItemInput.isBlank()) { TrackedItemsConfig.addItem(newItemInput.trim()); newItemInput = ""; }
                return true;
            }
            // Start scan
            int scanX = addX + 42;
            if (!MarketScanner.scanInProgress && mx >= scanX && mx < scanX + 40 && my >= y && my < y + 18) {
                MarketScanner.startFullScan(); return true;
            }
            // List buttons
            List<String> tracked = TrackedItemsConfig.getItems();
            int listY = y + 40, lineH = 18;
            int maxV = (py + H - listY - 22) / lineH;
            for (int i = watchScroll; i < Math.min(tracked.size(), watchScroll + maxV); i++) {
                int iy = listY + (i - watchScroll) * lineH;
                int cX = x + iw - 56, cY = iy + 2, rX = cX + 32;
                if (mx >= cX && mx < cX + 28 && my >= cY && my < cY + 14) { selectedItem = tracked.get(i); tab = 1; return true; }
                if (mx >= rX && mx < rX + 18 && my >= cY && my < cY + 14) { TrackedItemsConfig.removeItem(tracked.get(i)); return true; }
            }
        } else if (tab == 1) {
            if (selectedItem != null) {
                int bY = py + H - 52, aw = W - 12;
                if (mx >= px + 6 && mx < px + 6 + aw && my >= bY && my < bY + 16) {
                    assert client != null; client.setScreen(null);
                    MarketAutomator.suggestAndSell(selectedItem); return true;
                }
            }
        } else if (tab == 3) {
            int sx = px + 14, sy = py + 54;
            // Toggle Auto-Scan — fixed Y offset to match renderSettings
            int togX = sx + 130, togW = 70;
            if (mx >= togX && mx < togX + togW && my >= sy + 18 && my < sy + 34) {
                AutoScanScheduler.setEnabled(!AutoScanScheduler.isEnabled()); return true;
            }
            // Scan Interval
            int[] intervals = {1, 2, 5, 10};
            int curX = sx + 130;
            for (int iv : intervals) {
                if (mx >= curX && mx < curX + 32 && my >= sy + 42 && my < sy + 42 + 16) {
                    AutoScanScheduler.setIntervalMinutes(iv); return true;
                }
                curX += 36;
            }
            // Snipe Threshold
            int[] thresholds = {10, 20, 30, 50};
            curX = sx + 130;
            for (int thresholdValue : thresholds) {
                if (mx >= curX && mx < curX + 32 && my >= sy + 66 && my < sy + 66 + 16) {
                    AutoScanScheduler.setSnipeThreshold(thresholdValue); return true;
                }
                curX += 36;
            }
            // Export CSV
            int expW = 160, expX = px + W / 2 - expW / 2, expY = sy + 106;
            if (mx >= expX && mx < expX + expW && my >= expY && my < expY + 18) {
                MarketDataStore.exportToCsv();
                assert client != null; if (client.player != null) client.player.sendMessage(Text.literal("§a[Market] §fData exported to CSV!"), false);
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean charTyped(char c, int mod) {
        if (inputFocused) {
            newItemInput += c;
            updateSuggestions();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (inputFocused) {
            // Backspace
            if (key == 259) {
                if (!newItemInput.isEmpty()) {
                    newItemInput = newItemInput.substring(0, newItemInput.length() - 1);
                    updateSuggestions();
                }
                return true;
            }
            // Arrow Down
            if (key == 264) {
                hoveredSuggestion = Math.min(suggestions.size() - 1, hoveredSuggestion + 1);
                if (hoveredSuggestion >= suggestionScroll + MAX_SUGGESTIONS)
                    suggestionScroll = Math.min(suggestions.size() - MAX_SUGGESTIONS, suggestionScroll + 1);
                return true;
            }
            // Arrow Up
            if (key == 265) {
                hoveredSuggestion = Math.max(0, hoveredSuggestion - 1);
                if (hoveredSuggestion < suggestionScroll)
                    suggestionScroll = Math.max(0, suggestionScroll - 1);
                return true;
            }
            // Enter — confirm suggestion or add as-is
            if (key == 257 || key == 335) {
                if (hoveredSuggestion >= 0 && hoveredSuggestion < suggestions.size()) {
                    newItemInput = suggestions.get(hoveredSuggestion).split("\\|")[1];
                    suggestions.clear();
                    hoveredSuggestion = -1;
                } else if (!newItemInput.isBlank()) {
                    TrackedItemsConfig.addItem(newItemInput.trim());
                    newItemInput = "";
                    suggestions.clear();
                }
                return true;
            }
            // Escape — close dropdown
            if (key == 256) { inputFocused = false; suggestions.clear(); return true; }
            // Tab — autocomplete first suggestion
            if (key == 258 && !suggestions.isEmpty()) {
                newItemInput = suggestions.get(0).split("\\|")[1];
                suggestions.clear();
                hoveredSuggestion = -1;
                return true;
            }
            return true;
        }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double ha, double va) {
        // Scroll autocomplete dropdown if open
        int x = px + 6, y = py + 48;
        int fieldW = W - 12 - 90;
        int dropY = y + 18, dropH = Math.min(suggestions.size(), MAX_SUGGESTIONS) * 13 + 2;
        if (inputFocused && !suggestions.isEmpty()
                && mx >= x && mx <= x + fieldW
                && my >= dropY && my <= dropY + dropH) {
            suggestionScroll = Math.max(0, Math.min(suggestions.size() - MAX_SUGGESTIONS,
                    suggestionScroll - (int) va));
            return true;
        }
        if (tab == 0) watchScroll = Math.max(0, watchScroll - (int) va);
        return true;
    }

    /**
     * Disable Minecraft's built-in screen blur + dark overlay.
     * We draw our own fully opaque panel.
     */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // intentionally empty — prevents blur
    }

    @Override
    public boolean shouldPause() { return false; }
}
