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
        if (MarketScanner.scanInProgress && MarketScanner.isAutoScan && screen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs) {
            // Przechwycenie ekranu bez jego otwierania w UI (Silent Scan)
            MarketScanner.captureScreen(hs);
            ci.cancel();
        }
    }
}
