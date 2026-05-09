package org.example.marketanalyzer.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.example.marketanalyzer.logic.MarketScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        if (MarketScanner.scanInProgress && MarketScanner.isAutoScan && screen != null) {
            // When an AH screen is set during auto-scan, we allow the setScreen logic to proceed 
            // so the network handler registers the container, but we might want to skip some parts.
            // However, the main goal is to keep the mouse locked.
        }
    }
}
