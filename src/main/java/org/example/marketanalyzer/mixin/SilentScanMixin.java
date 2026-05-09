package org.example.marketanalyzer.mixin;
 
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.DrawContext;
import org.example.marketanalyzer.logic.MarketScanner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class SilentScanMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (MarketScanner.scanInProgress && MarketScanner.isAutoScan) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void onRenderBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (MarketScanner.scanInProgress && MarketScanner.isAutoScan) {
            ci.cancel();
        }
    }
}

@Mixin(net.minecraft.client.Mouse.class)
abstract class MouseMixin {
    @Inject(method = "unlockCursor", at = @At("HEAD"), cancellable = true)
    private void onUnlockCursor(CallbackInfo ci) {
        if (MarketScanner.scanInProgress && MarketScanner.isAutoScan) {
            ci.cancel();
        }
    }
}
