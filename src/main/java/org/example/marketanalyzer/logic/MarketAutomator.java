package org.example.marketanalyzer.logic;
 
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.example.marketanalyzer.MarketAnalyzerMod;
import org.example.marketanalyzer.data.MarketDataStore;
import org.example.marketanalyzer.data.MarketItem;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import java.util.Locale;

public class MarketAutomator {

    public static boolean waitingForSellConfirm = false;
    public static long sellConfirmTimeout = 0;
    private static String batchSellItem = null;
    private static final int TARGET_HOTBAR_SLOT = 4; // Slot 5 (0-indexed)

    public static void init() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (waitingForSellConfirm) {
                if (System.currentTimeMillis() > sellConfirmTimeout) {
                    waitingForSellConfirm = false;
                    batchSellItem = null;
                    if (client.player != null) client.player.sendMessage(Text.literal("§c[Auto-Sell] Timeout: Confirmation GUI not found!"), false);
                    return;
                }
                
                if (client.currentScreen instanceof GenericContainerScreen screen) {
                    GenericContainerScreenHandler handler = screen.getScreenHandler();
                    for (int i = 0; i < handler.slots.size(); i++) {
                        ItemStack stack = handler.getSlot(i).getStack();
                        if (stack.getItem() == net.minecraft.item.Items.LIME_STAINED_GLASS_PANE) {
                            client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            waitingForSellConfirm = false;
                            
                            if (client.player != null) {
                                client.player.sendMessage(Text.literal("§a[Auto-Sell] Listed one stack!"), false);
                                client.player.closeHandledScreen();
                            }
                            
                            if (batchSellItem != null) {
                                final String itemToContinue = batchSellItem;
                                new Thread(() -> {
                                    try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                                    client.execute(() -> suggestAndSell(itemToContinue));
                                }).start();
                            }
                            break;
                        }
                    }
                }
            }
        });
    }

    public static void suggestAndSell(String itemId) {
        batchSellItem = itemId;
        MarketItem itemData = MarketDataStore.getItem(itemId);
        if (itemData == null || itemData.getHistory().isEmpty()) {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§cNo price data for " + getFriendlyName(itemId)), false);
            batchSellItem = null;
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        PlayerInventory inv = client.player.getInventory();

        // 1. Check item in hand first
        int sourceSlot = -1;
        ItemStack heldStack = inv.getStack(inv.selectedSlot);
        if (!heldStack.isEmpty()) {
            String heldId = net.minecraft.registry.Registries.ITEM.getId(heldStack.getItem()).toString();
            if (heldId.equals(itemId)) {
                sourceSlot = inv.selectedSlot;
            }
        }

        // 2. Search entire inventory by Registry ID
        if (sourceSlot == -1) {
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) continue;
                
                String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
                if (id.equals(itemId)) {
                    sourceSlot = i;
                    break;
                }
            }
        }

        if (sourceSlot == -1) {
            if (batchSellItem != null) {
                client.player.sendMessage(Text.literal("§6[Auto-Sell] All items sold: " + getFriendlyName(itemId)), false);
            }
            batchSellItem = null;
            return;
        }

        // 3. Prepare item in slot 5
        if (sourceSlot != TARGET_HOTBAR_SLOT) {
            int containerSlotId = (sourceSlot < 9) ? sourceSlot + 36 : sourceSlot;
            client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, 
                containerSlotId, TARGET_HOTBAR_SLOT, SlotActionType.SWAP, client.player);
            
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }
        inv.selectedSlot = TARGET_HOTBAR_SLOT;

        // 4. Calculate price
        double unitPrice = itemData.getLowestRecentPrice(24 * 3600000L);
        if (unitPrice <= 0) unitPrice = itemData.getHistory().get(itemData.getHistory().size()-1).getPrice();
        
        double finalUnitPrice = (unitPrice > 100) ? unitPrice - 1.0 : unitPrice * 0.99;
        if (finalUnitPrice < 0.1) finalUnitPrice = 0.1;

        ItemStack stackInHand = inv.getStack(TARGET_HOTBAR_SLOT);
        int count = stackInHand.getCount();
        double totalPrice = finalUnitPrice * count;

        String priceStr = String.format(Locale.US, "%.0f", totalPrice);
        client.player.sendMessage(Text.literal("§e[Auto-Sell] Listing " + count + "x " + getFriendlyName(itemId) + " for $" + priceStr), false);

        // 5. Send command
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            client.execute(() -> {
                if (client.player != null) {
                    client.player.networkHandler.sendCommand("ah sell " + priceStr);
                    waitingForSellConfirm = true;
                    sellConfirmTimeout = System.currentTimeMillis() + 4000;
                }
            });
        }).start();
    }

    private static String getFriendlyName(String id) {
        if (!id.contains(":")) return id;
        String name = id.substring(id.indexOf(":") + 1).replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    public static boolean isSnipe(String itemId, double currentPrice) {
        MarketItem item = MarketDataStore.getItem(itemId);
        if (item == null) return false;
        double avgPrice = item.getAverageRecentPrice(7 * 24 * 3600000L);
        if (avgPrice <= 0) return false;
        double thresholdPerc = AutoScanScheduler.getSnipeThreshold() / 100.0;
        return currentPrice <= (avgPrice * (1.0 - thresholdPerc));
    }

    public static void playSnipeSound() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
