package org.example.marketanalyzer.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.example.marketanalyzer.MarketAnalyzerMod;
import org.example.marketanalyzer.data.MarketDataStore;
import org.example.marketanalyzer.data.MarketItem;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates prices.json for the AutoBuy mod (upoWiewiur).
 * Written to: config/marketanalyzer/prices.json
 *
 * Format:
 * {
 *   "minecraft:carrot": {
 *     "avgPrice": 1234.56,
 *     "lowestPrice": 1100.0,
 *     "recommendation": "BUY" | "SELL" | "HOLD",
 *     "lastUpdated": 1715123456789
 *   }
 * }
 */
public class PricesJsonExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_DIR = FabricLoader.getInstance()
        .getConfigDir().resolve("marketanalyzer").toFile();
    private static final File PRICES_FILE = new File(CONFIG_DIR, "prices.json");

    // Time windows for analysis
    private static final long WINDOW_7D  = 7L  * 24 * 3_600_000;
    private static final long WINDOW_24H = 24L       * 3_600_000;

    private static class PriceEntry {
        double avgPrice;
        double lowestPrice;
        double highestPrice;
        int    dataPoints;
        String recommendation; // BUY | SELL | HOLD
        long   lastUpdated;
    }

    /**
     * Exports the prices.json file. Called after each full scan completes.
     * Thread-safe — runs synchronously (called from the scan-complete path).
     */
    public static void export() {
        Map<String, PriceEntry> output = new HashMap<>();

        for (Map.Entry<String, MarketItem> entry : MarketDataStore.getAllData().entrySet()) {
            MarketItem item = entry.getValue();
            if (item.getHistory().isEmpty()) continue;

            double avg7d   = item.getAverageRecentPrice(WINDOW_7D);
            double lowest24 = item.getLowestRecentPrice(WINDOW_24H);
            double latest  = item.getHistory().get(item.getHistory().size() - 1).getPrice();

            if (avg7d <= 0) continue;

            PriceEntry e = new PriceEntry();
            e.avgPrice    = avg7d;
            e.lowestPrice = lowest24 > 0 ? lowest24 : latest;
            e.dataPoints  = item.getHistory().size();
            e.lastUpdated = System.currentTimeMillis();

            // Recommendation logic:
            // BUY  — current lowest is >= 10% below 7d average (good deal)
            // SELL — current price is >= 5% above 7d average (sell now!)
            // HOLD — price is within normal range
            double ratio = latest / avg7d;
            if (ratio <= 0.90) {
                e.recommendation = "BUY";
            } else if (ratio >= 1.05) {
                e.recommendation = "SELL";
            } else {
                e.recommendation = "HOLD";
            }

            // Highest price in last 24h
            double maxPrice = 0;
            long cutoff = System.currentTimeMillis() - WINDOW_24H;
            for (var pt : item.getHistory()) {
                if (pt.getTimestamp() >= cutoff && pt.getPrice() > maxPrice) {
                    maxPrice = pt.getPrice();
                }
            }
            e.highestPrice = maxPrice > 0 ? maxPrice : latest;

            output.put(entry.getKey(), e);
        }

        if (output.isEmpty()) return;

        if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();
        try (FileWriter writer = new FileWriter(PRICES_FILE)) {
            GSON.toJson(output, writer);
            MarketAnalyzerMod.LOGGER.info("[PricesExporter] Wyeksportowano " + output.size()
                + " przedmiotów do prices.json");
        } catch (Exception e) {
            MarketAnalyzerMod.LOGGER.error("[PricesExporter] Błąd zapisu prices.json", e);
        }
    }
}
