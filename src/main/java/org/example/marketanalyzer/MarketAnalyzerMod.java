package org.example.marketanalyzer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MarketAnalyzerMod implements ClientModInitializer {
    public static final String MOD_ID = "marketanalyzerdonutsmp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Bindy klawiaturowe — gracz ustawia je w Options > Controls > Market Analyzer
    public static KeyBinding openMenuKey;   // otwiera GUI moda
    public static KeyBinding autoSellKey;   // sprzedaje przedmiot trzymany w ręce

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Market] Market Analyzer for DonutSMP initializing...");

        // Load data
        org.example.marketanalyzer.data.MarketDataStore.load();
        org.example.marketanalyzer.data.TrackedItemsConfig.load();

        // Init scanner
        org.example.marketanalyzer.logic.MarketScanner.init();

        // Init auto-scan scheduler (domyślnie wyłączony — gracz włącza ręcznie w GUI)
        org.example.marketanalyzer.logic.AutoScanScheduler.init();
        
        // Init automator
        org.example.marketanalyzer.logic.MarketAutomator.init();

        // ── Bind: Otwórz menu ──
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.marketanalyzer.open_menu",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,          // bez przypisania domyślnego — gracz ustawia sam
            "key.categories.marketanalyzer"
        ));

        // ── Bind: Auto-Sell (trzymany przedmiot) ──
        autoSellKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.marketanalyzer.autosell",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.marketanalyzer"
        ));
        // UWAGA: ręczny bind do skanu usunięto — używaj auto-skanu lub przycisku ▶ w GUI.

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Otwórz menu
            while (openMenuKey.wasPressed()) {
                client.setScreen(new org.example.marketanalyzer.gui.MarketAnalyzerScreen(client.currentScreen));
            }

            // Auto-Sell trzymanego przedmiotu
            while (autoSellKey.wasPressed()) {
                net.minecraft.item.ItemStack held = client.player.getMainHandStack();
                if (!held.isEmpty()) {
                    // Używamy unikalnego ID (np. minecraft:carrot) zamiast nazwy wyświetlanej
                    String id = net.minecraft.registry.Registries.ITEM.getId(held.getItem()).toString();
                    org.example.marketanalyzer.logic.MarketAutomator.suggestAndSell(id);
                } else {
                    client.player.sendMessage(net.minecraft.text.Text.literal("§c[Market] §fHold an item in your hand!"), false);
                }
            }
        });
    }
}
