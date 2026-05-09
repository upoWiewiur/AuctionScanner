package org.example.marketanalyzer.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.example.marketanalyzer.MarketAnalyzerMod;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores the list of item names the user wants to track.
 * Saved to config/marketanalyzer/tracked_items.json
 */
public class TrackedItemsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("marketanalyzer").toFile();
    private static final File FILE = new File(CONFIG_DIR, "config.json");

    private static ConfigData data = new ConfigData();

    public static class ConfigData {
        public List<String> trackedItems = new ArrayList<>();
        public String apiServerUrl = "https://auctionscanner.onrender.com";
    }

    public static List<String> getItems() {
        return data.trackedItems;
    }

    public static String getApiUrl() {
        String url = data.apiServerUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static void setApiUrl(String url) {
        data.apiServerUrl = url;
        save();
    }

    public static void addItem(String name) {
        String clean = name.trim();
        if (!clean.isEmpty() && !data.trackedItems.contains(clean)) {
            data.trackedItems.add(clean);
            save();
        }
    }

    public static void removeItem(String name) {
        data.trackedItems.remove(name.trim());
        save();
    }

    public static void load() {
        if (!FILE.exists()) {
            File legacy = new File(CONFIG_DIR, "tracked_items.json");
            if (legacy.exists()) {
                try (FileReader r = new FileReader(legacy)) {
                    Type type = new TypeToken<List<String>>(){}.getType();
                    List<String> loaded = GSON.fromJson(r, type);
                    if (loaded != null) data.trackedItems = loaded;
                    save();
                    legacy.delete();
                } catch (Exception ignored) {}
            }
            return;
        }
        try (FileReader r = new FileReader(FILE)) {
            ConfigData loaded = GSON.fromJson(r, ConfigData.class);
            if (loaded != null) data = loaded;
        } catch (IOException e) {
            MarketAnalyzerMod.LOGGER.error("[Market] Failed to load config", e);
        }
    }

    public static void save() {
        if (!CONFIG_DIR.exists()) CONFIG_DIR.mkdirs();
        try (FileWriter w = new FileWriter(FILE)) {
            GSON.toJson(data, w);
        } catch (IOException e) {
            MarketAnalyzerMod.LOGGER.error("[Market] Failed to save config", e);
        }
    }
}
