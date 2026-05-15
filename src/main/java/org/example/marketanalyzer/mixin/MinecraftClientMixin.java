package org.example.marketanalyzer.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import org.example.marketanalyzer.logic.MarketScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void onSetScreen(Screen screen, CallbackInfo ci) {
        // Intercept BOTH manual and auto scans — prevent GUI from ever rendering
        if (MarketScanner.scanInProgress && screen instanceof HandledScreen<?> hs) {
            MarketScanner.captureScreen(hs);
            // Tell the server we closed the container immediately,
            // so the player is NEVER locked in place on the server side
            MinecraftClient mc = (MinecraftClient)(Object)this;
            if (mc.player != null && mc.player.currentScreenHandler != null) {
                mc.player.networkHandler.sendPacket(
                    new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
            }
            ci.cancel(); // Don't render any GUI - true silent scan
        }
    }
}
