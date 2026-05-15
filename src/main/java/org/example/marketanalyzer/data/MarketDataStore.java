package org.example.marketanalyzer.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.example.marketanalyzer.MarketAnalyzerMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MarketDataStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("marketanalyzer").toFile();
    private static final File DATA_FILE = new File(CONFIG_DIR, "market_data.json");

    private static Map<String, MarketItem> marketData = new HashMap<>();

    public static void load() {
        if (!DATA_FILE.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(DATA_FILE)) {
            Type type = new TypeToken<Map<String, MarketItem>>() {}.getType();
            Map<String, MarketItem> loadedData = GSON.fromJson(reader, type);
            if (loadedData != null) {
                marketData = loadedData;
                long fourteenDays = 14L * 24 * 3600 * 1000L;
                int prunedTotal = 0;
                for (MarketItem item : marketData.values()) {
                    int before = item.getHistory().size();
                    item.pruneOldData(fourteenDays);
                    prunedTotal += (before - item.getHistory().size());
                }
                MarketAnalyzerMod.LOGGER.info("Loaded market data for " + marketData.size() + " items. Pruned " + prunedTotal + " old records.");
            }
        } catch (IOException e) {
            MarketAnalyzerMod.LOGGER.error("Failed to load market data", e);
        }
        
        // Asynchroniczne pobranie globalnej historii po załadowaniu lokalnych danych
        pullFromServerAsync();
        startPeriodicPull();
    }

    private static void startPeriodicPull() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10 * 60 * 1000L); // every 10 minutes
                    pullFromServerAsync();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        t.setDaemon(true); // won't prevent JVM shutdown
        t.start();
    }

    public static void save() {
        if (!CONFIG_DIR.exists()) {
            CONFIG_DIR.mkdirs();
        }

        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            GSON.toJson(marketData, writer);
        } catch (IOException e) {
            MarketAnalyzerMod.LOGGER.error("Failed to save market data", e);
        }
        
        // Wysłanie nowych danych na serwer (crowdsourcing) po każdym zapisie
        pushToServerAsync();
    }

    public static MarketItem getItem(String itemName) {
        return marketData.get(itemName);
    }

    public static void recordPrice(String itemName, double price, int volume) {
        MarketItem item = marketData.computeIfAbsent(itemName, MarketItem::new);
        item.addPrice(price, volume);
    }

    public static Map<String, MarketItem> getAllData() {
        return marketData;
    }

    public static void exportToCsv() {
        File csvFile = new File(CONFIG_DIR, "market_data_export.csv");
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("ItemName,Timestamp,Price\n");
            for (MarketItem item : marketData.values()) {
                for (PricePoint pt : item.getHistory()) {
                    writer.write(String.format("%s,%d,%f\n", 
                        item.getItemName().replace(",", ""), 
                        pt.getTimestamp(), 
                        pt.getPrice()));
                }
            }
            MarketAnalyzerMod.LOGGER.info("Exported market data to " + csvFile.getAbsolutePath());
        } catch (IOException e) {
            MarketAnalyzerMod.LOGGER.error("Failed to export market data", e);
        }
    }

    private static String getServerUrl() {
        return TrackedItemsConfig.getApiUrl() + "/api/market-data";
    }

    public static void pushToServerAsync() {
        if (marketData.isEmpty()) return;
        if (!org.example.marketanalyzer.logic.MarketScanner.isOnDonutSMP()) return;
        
        new Thread(() -> {
            try {
                Map<String, Object> wrapper = new HashMap<>();
                wrapper.put("server", "donutsmp.net");
                wrapper.put("data", marketData);
                
                String json = GSON.toJson(wrapper);
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(getServerUrl()))
                        .header("Content-Type", "application/json")
                        .header("X-API-Key", TrackedItemsConfig.getApiKey())
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                        .build();
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                MarketAnalyzerMod.LOGGER.info("[MarketAnalyzer] Pomyślnie wysłano dane na serwer centralny.");
            } catch (Exception e) {
                MarketAnalyzerMod.LOGGER.warn("[MarketAnalyzer] Serwer centralny jest niedostępny.");
            }
        }).start();
    }

    /**
     * DTO matching the server API response format:
     * { "minecraft:carrot": { "itemName": "minecraft:carrot", "history": [{"timestamp":...,"price":...,"volume":...}] } }
     */
    private static class ServerItemDto {
        String itemName;
        List<PricePoint> history = new ArrayList<>();
    }

    public static void pullFromServerAsync() {
        Thread t = new Thread(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(getServerUrl()))
                        .header("X-API-Key", TrackedItemsConfig.getApiKey())
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    Type type = new TypeToken<Map<String, ServerItemDto>>() {}.getType();
                    Map<String, ServerItemDto> globalData = GSON.fromJson(response.body(), type);

                    if (globalData == null || globalData.isEmpty()) return;

                    int added = 0;
                    synchronized (marketData) {
                        for (Map.Entry<String, ServerItemDto> entry : globalData.entrySet()) {
                            String itemKey = entry.getKey();
                            ServerItemDto dto = entry.getValue();
                            if (dto == null || dto.history == null) continue;

                            MarketItem localItem = marketData.computeIfAbsent(
                                itemKey, MarketItem::new);

                            // Build dedup set from existing history (O(1) lookup)
                            Set<Long> existingKeys = new HashSet<>();
                            for (PricePoint p : localItem.getHistory()) {
                                // Key = timestamp (unique enough for our use-case)
                                existingKeys.add(p.getTimestamp());
                            }

                            for (PricePoint pt : dto.history) {
                                if (pt == null) continue;
                                if (!existingKeys.contains(pt.getTimestamp())) {
                                    localItem.getHistory().add(pt);
                                    existingKeys.add(pt.getTimestamp());
                                    added++;
                                }
                            }
                            // Keep history sorted
                            localItem.getHistory().sort(
                                java.util.Comparator.comparingLong(PricePoint::getTimestamp));
                        }
                    }

                    // Copy for safe serialization outside synchronized block
                    Map<String, MarketItem> copyToSave;
                    synchronized (marketData) {
                        copyToSave = new HashMap<>(marketData);
                    }

                    // Persist merged data (skip pushToServerAsync to avoid loop)
                    if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();
                    try (FileWriter writer = new FileWriter(DATA_FILE)) {
                        GSON.toJson(copyToSave, writer);
                    }
                    MarketAnalyzerMod.LOGGER.info(
                        "[MarketAnalyzer] Pobrano globalną historię. Nowych punktów: " + added);
                } else {
                    MarketAnalyzerMod.LOGGER.warn(
                        "[MarketAnalyzer] Serwer odpowiedział: " + response.statusCode());
                }
            } catch (Exception e) {
                MarketAnalyzerMod.LOGGER.warn(
                    "[MarketAnalyzer] Nie udało się pobrać globalnej historii: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
