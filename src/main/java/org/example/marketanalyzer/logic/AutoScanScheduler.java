package org.example.marketanalyzer.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.example.marketanalyzer.MarketAnalyzerMod;
import org.example.marketanalyzer.data.TrackedItemsConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Automatyczny scheduler skanowania rynku.
 * - Domyślnie WYŁĄCZONY — gracz musi włączyć go ręcznie w GUI (zakładka Ustawienia)
 * - NIE ingeruje w grę: skan odpala się tylko gdy gracz nie ma otwartego żadnego GUI
 * - Ustawienia są zapisywane do JSON i wczytywane przy kolejnym starcie
 */
public class AutoScanScheduler {

    // ── Ustawienia (zapisywane) ────────────────────────────────
    private static boolean enabled = false;
    private static int intervalMinutes = 1; // domyślnie 1 minuta
    private static int snipeThreshold = 30; // 30% poniżej średniej

    // ── Stan wewnętrzny ───────────────────────────────────────
    private static int ticksRemaining = 0;
    private static long lastScanFinishedAt = 0L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir().resolve("marketanalyzer/auto_scan.json").toFile();

    // ── Init ─────────────────────────────────────────────────

    public static void init() {
        load();
        resetTimer();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled) return;
            if (client.player == null) return;

            // Nie przerywaj gracza — skanuj tylko gdy nie ma otwartego GUI
            if (client.currentScreen != null) return;

            // Nie nakładaj skanów na siebie
            if (MarketScanner.scanInProgress) return;

            // Nie ma czego skanować
            if (TrackedItemsConfig.getItems().isEmpty()) return;

            ticksRemaining--;
            if (ticksRemaining <= 0) {
                MarketAnalyzerMod.LOGGER.info("[AutoScan] Automatyczne skanowanie rynku...");
                MarketScanner.startAutoScan(); // cichy skan — bez wiadomości na czacie
                resetTimer();
            }
        });
    }

    // ── Wywoływane przez MarketScanner po zakończeniu skanu ───

    public static void onScanComplete() {
        lastScanFinishedAt = System.currentTimeMillis();
        resetTimer();
    }

    // ── Publiczne API ─────────────────────────────────────────

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (value) resetTimer();
        save();
        MarketAnalyzerMod.LOGGER.info("[AutoScan] Auto-skan " + (value ? "WŁĄCZONY" : "WYŁĄCZONY")
                + " (co " + intervalMinutes + " min)");
    }

    public static int getIntervalMinutes() { return intervalMinutes; }

    public static void setIntervalMinutes(int minutes) {
        intervalMinutes = Math.max(1, minutes);
        resetTimer();
        save();
    }

    public static int getSnipeThreshold() { return snipeThreshold; }

    public static void setSnipeThreshold(int threshold) {
        snipeThreshold = Math.max(1, Math.min(99, threshold));
        save();
    }

    /** Liczba ticków pozostałych do następnego skanu (20 ticków = 1 sekunda). */
    public static int getTicksRemaining() { return ticksRemaining; }

    /** Czas ostatniego zakończonego skanu (System.currentTimeMillis()), lub 0 jeśli nie było. */
    public static long getLastScanFinishedAt() { return lastScanFinishedAt; }

    /**
     * Czytelny string odliczania do następnego skanu, np. "0:47".
     * Zwraca "—" jeśli auto-skan jest wyłączony.
     */
    public static String getCountdownString() {
        if (!enabled) return "—";
        int secs = Math.max(0, ticksRemaining / 20);
        return String.format("%d:%02d", secs / 60, secs % 60);
    }

    /**
     * Ile czasu minęło od ostatniego skanu, np. "2 min temu" lub "przed chwilą".
     */
    public static String getLastScanAgoString() {
        if (lastScanFinishedAt == 0) return "jeszcze nie skanowano";
        long agoSec = (System.currentTimeMillis() - lastScanFinishedAt) / 1000;
        if (agoSec < 60) return "przed chwilą";
        return (agoSec / 60) + " min temu";
    }

    // ── Wewnętrzne ────────────────────────────────────────────

    private static void resetTimer() {
        ticksRemaining = intervalMinutes * 60 * 20;
    }

    private static void load() {
        if (!CONFIG_FILE.exists()) return;
        try (FileReader r = new FileReader(CONFIG_FILE)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            if (obj.has("enabled"))         enabled = obj.get("enabled").getAsBoolean();
            if (obj.has("intervalMinutes")) intervalMinutes = obj.get("intervalMinutes").getAsInt();
            if (obj.has("snipeThreshold"))  snipeThreshold = obj.get("snipeThreshold").getAsInt();
            MarketAnalyzerMod.LOGGER.info("[AutoScan] Wczytano ustawienia (enabled=" + enabled
                    + ", interval=" + intervalMinutes + "min, snipeThr=" + snipeThreshold + "%)");
        } catch (Exception e) {
            MarketAnalyzerMod.LOGGER.error("[AutoScan] Błąd wczytywania ustawień", e);
        }
    }

    private static void save() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("intervalMinutes", intervalMinutes);
            obj.addProperty("snipeThreshold", snipeThreshold);
            try (FileWriter w = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(obj, w);
            }
        } catch (Exception e) {
            MarketAnalyzerMod.LOGGER.error("[AutoScan] Błąd zapisywania ustawień", e);
        }
    }
}
