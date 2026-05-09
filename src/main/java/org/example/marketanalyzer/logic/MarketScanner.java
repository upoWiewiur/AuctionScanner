package org.example.marketanalyzer.logic;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.example.marketanalyzer.MarketAnalyzerMod;
import org.example.marketanalyzer.data.MarketDataStore;
import org.example.marketanalyzer.data.TrackedItemsConfig;

import java.util.*;

/**
 * Scans via /ah {itemname} for each tracked item, one at a time.
 * Phase 1: send /ah command, wait for screen
 * Phase 2: wait 30 ticks for slots to fill
 * Phase 3: read slots, record prices
 * Phase 4: close screen, wait 10 ticks, do next item
 */
public class MarketScanner {

    public enum State { IDLE, WAITING_SCREEN, WAITING_SLOTS, SCANNING, COOLDOWN }

    public static State state = State.IDLE;
    private static Queue<String> queue = new LinkedList<>();
    private static HandledScreen<?> capturedScreen = null;
    private static int tickCounter = 0;
    private static String currentItem = null;
    private static boolean didScan = false;  // true after doScan, waiting to close screen

    public static int lastPricesFound = 0;
    public static String lastScannedItem = "";
    public static boolean scanInProgress = false;
    public static boolean isAutoScan = false; // true = uruchomiony przez AutoScanScheduler

    private static final int SLOT_WAIT_TICKS = 25;  // time for server to send item data
    private static final int COOLDOWN_TICKS   = 15;  // pause between items

    // ── INIT ─────────────────────────────────────────────────

    public static void init() {
        // Capture any HandledScreen that opens while we're waiting
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (state == State.WAITING_SCREEN && screen instanceof HandledScreen<?> hs) {
                capturedScreen = hs;
                state = State.WAITING_SLOTS;
                tickCounter = 0;
                MarketAnalyzerMod.LOGGER.info("[Market] Screen opened for '" + currentItem + "'");
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            switch (state) {
                case IDLE -> {} // nothing

                case WAITING_SCREEN -> {
                    tickCounter++;
                    if (tickCounter > 60) { // timeout — skip item
                        MarketAnalyzerMod.LOGGER.warn("[Market] Timeout waiting for screen for '" + currentItem + "'");
                        advanceQueue(client);
                    }
                }

                case WAITING_SLOTS -> {
                    tickCounter++;
                    if (tickCounter >= SLOT_WAIT_TICKS) {
                        state = State.SCANNING;
                    }
                }

                case SCANNING -> {
                    if (!didScan) {
                        // Tick 1: perform the scan while the screen is still open
                        if (capturedScreen != null && client.currentScreen == capturedScreen) {
                            doScan(client, capturedScreen);
                        } else {
                            MarketAnalyzerMod.LOGGER.warn("[Market] Screen was closed before scan for '" + currentItem + "'");
                        }
                        didScan = true;  // next tick will close the screen
                    } else {
                        // Tick 2: close the AH screen and move on
                        client.setScreen(null);
                        capturedScreen = null;
                        didScan = false;
                        state = State.COOLDOWN;
                        tickCounter = 0;
                    }
                }

                case COOLDOWN -> {
                    tickCounter++;
                    if (tickCounter >= COOLDOWN_TICKS) {
                        advanceQueue(client);
                    }
                }
            }
        });
    }

    // ── PUBLIC API ────────────────────────────────────────────

    /** Start scanning all tracked items (ręczne wywołanie). */
    public static void startFullScan() {
        if (!isOnDonutSMP()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) client.player.sendMessage(Text.literal("§c[Market] §fMod działa tylko na serwerze §eDonutSMP.net"), false);
            return;
        }
        if (scanInProgress) return;
        List<String> items = TrackedItemsConfig.getItems();
        if (items.isEmpty()) return;
        isAutoScan = false;
        queue = new LinkedList<>(items);
        scanInProgress = true;
        didScan = false;
        PriceParser.resetDebugLogCount();
        MarketAnalyzerMod.LOGGER.info("[Market] Starting full scan of " + items.size() + " items.");
        startNextItem(MinecraftClient.getInstance());
    }

    /** Start scanning all tracked items (wywołanie z AutoScanScheduler — bez wiadomości). */
    public static void startAutoScan() {
        if (!isOnDonutSMP()) return;
        if (scanInProgress) return;
        List<String> items = TrackedItemsConfig.getItems();
        if (items.isEmpty()) return;
        isAutoScan = true;
        queue = new LinkedList<>(items);
        scanInProgress = true;
        didScan = false;
        PriceParser.resetDebugLogCount();
        MarketAnalyzerMod.LOGGER.info("[Market] Auto-scan of " + items.size() + " items.");
        startNextItem(MinecraftClient.getInstance());
    }

    public static boolean isOnDonutSMP() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getCurrentServerEntry() == null) return false;
        String addr = client.getCurrentServerEntry().address.toLowerCase();
        return addr.contains("donutsmp.net");
    }

    public static void stopScan() {
        queue.clear();
        state = State.IDLE;
        scanInProgress = false;
        isAutoScan = false;
        capturedScreen = null;
    }

    // ── INTERNAL ─────────────────────────────────────────────

    private static void startNextItem(MinecraftClient client) {
        if (queue.isEmpty()) {
            state = State.IDLE;
            scanInProgress = false;
            isAutoScan = false; // Reset flag
            MarketDataStore.save();
            AutoScanScheduler.onScanComplete();
            // Wyświetl wiadomość tylko przy ręcznym skanie
            if (!isAutoScan && client.player != null) {
                client.player.sendMessage(
                    Text.literal("§a[Market] §fSkanowanie zakończone! Sprawdź wykresy (§eM§f)."), false);
            }
            return;
        }

        currentItem = queue.poll();
        lastScannedItem = currentItem;
        state = State.WAITING_SCREEN;
        tickCounter = 0;

        // Send /ah command using the path part only (e.g. minecraft:carrot -> carrot)
        if (client.player != null) {
            String searchTerm = currentItem.contains(":") ? currentItem.split(":")[1] : currentItem;
            client.player.networkHandler.sendCommand("ah " + searchTerm);
            MarketAnalyzerMod.LOGGER.info("[Market] Sent /ah " + searchTerm);
        }
    }

    private static void advanceQueue(MinecraftClient client) {
        state = State.IDLE;
        startNextItem(client);
    }

    private static void doScan(MinecraftClient client, HandledScreen<?> screen) {
        if (screen.getScreenHandler() == null) return;

        double minUnit = Double.MAX_VALUE;
        int bestCount = 0;
        int foundTotal = 0;
        String itemIdentifier = "unknown";

        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            if (client.player != null && slot.inventory == client.player.getInventory()) continue;

            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            if (isUIItem(stack.getName().getString())) continue;
            
            // TYLKO przedmioty pasujące do tego, co skanujemy (lub zawierające w nazwie, jeśli skanujemy po nazwie)
            // Jednak najbezpieczniej jest trzymać się ID lub nazwy z currentItem
            String currentLower = currentItem.toLowerCase();
            if (!id.contains(currentLower) && !stack.getName().getString().toLowerCase().contains(currentLower)) continue;

            List<net.minecraft.text.Text> tooltip = stack.getTooltip(
                net.minecraft.item.Item.TooltipContext.create(client.world),
                client.player,
                TooltipType.BASIC
            );

            double price = PriceParser.parsePriceFromLore(stack, tooltip);
            if (price > 0) {
                int count = Math.max(1, stack.getCount());
                double unit = price / count;
                
                if (unit < minUnit) {
                    minUnit = unit;
                    bestCount = count;
                    itemIdentifier = id;
                }
                foundTotal++;
            }
        }

        if (foundTotal > 0 && !itemIdentifier.equals("unknown")) {
            // Zapisujemy pod unikalnym ID (np. minecraft:carrot)
            MarketDataStore.recordPrice(itemIdentifier, minUnit, bestCount);

            // Sprawdzenie okazji (Snipe)
            if (MarketAutomator.isSnipe(itemIdentifier, minUnit) && client.player != null) {
                double avg = MarketDataStore.getItem(itemIdentifier).getAverageRecentPrice(7 * 24 * 3600000L);
                MarketAutomator.playSnipeSound();
                
                String snipeMsg = "§6[Snipe!] §e" + getFriendlyName(itemIdentifier) + " §fza §a$" + fmt(minUnit * bestCount) + " §7(avg: $" + fmt(avg) + ")";
                client.player.sendMessage(Text.literal(snipeMsg), false);
                
                if (isAutoScan) {
                    client.player.sendMessage(Text.literal("§aDeal: §e" + getFriendlyName(itemIdentifier) + " §a$" + fmt(minUnit)), true);
                }

                // Wysyłamy informację o okazji na serwer
                sendSnipeToServer(itemIdentifier, minUnit, avg);
            }
        }

        lastPricesFound = foundTotal;
        MarketAnalyzerMod.LOGGER.info("[Market] '" + itemIdentifier + "' → Found " + foundTotal + " offers, best: $" + String.format("%.1f", minUnit));
    }

    private static void sendSnipeToServer(String itemId, double price, double avg) {
        new Thread(() -> {
            try {
                String json = String.format(java.util.Locale.US, "{\"item_name\":\"%s\",\"price\":%.2f,\"avg_price\":%.2f}", itemId, price, avg);
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(TrackedItemsConfig.getApiUrl() + "/api/snipes"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                        .build();
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        }).start();
    }

    private static String getFriendlyName(String id) {
        // Zamiana minecraft:carrot -> Carrot
        String name = id.substring(id.indexOf(":") + 1).replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private static boolean isUIItem(String name) {
        String l = name.toLowerCase();
        // English UI buttons
        if (l.isEmpty() || l.contains("next") || l.contains("prev") || l.contains("back")
            || l.contains("close") || l.contains("search") || l.contains("filter")
            || l.contains("sort") || l.contains("info") || l.contains("help")
            || l.contains(">>>") || l.contains("<<<") || l.contains("page")) return true;
        // Polish UI buttons (DonutSMP may use Polish)
        if (l.contains("następ") || l.contains("poprz") || l.contains("zamknij")
            || l.contains("strona") || l.contains("filtr") || l.contains("sortuj")
            || l.contains("szukaj") || l.contains("wyszuk") || l.contains("powrót")
            || l.contains("powrot") || l.contains("pomoc")) return true;
        return false;
    }

    public static void resetScanFlag() {} // no-op, kept for compatibility

    public static String fmt(double p) {
        if (p >= 1_000_000_000) return String.format("%.2fB", p / 1_000_000_000);
        if (p >= 1_000_000)     return String.format("%.2fM", p / 1_000_000);
        if (p >= 1_000)         return String.format("%.1fK", p / 1_000);
        return String.format("%.0f", p);
    }
}
