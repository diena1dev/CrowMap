package dev.diena.crowmap.mixin.client;

import dev.diena.crowmap.client.config.CrowmapConfig;
import io.wispforest.owo.config.ui.ConfigScreen;
import kotlin.Suppress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionsSubScreen.class)
public abstract class PauseScreenMixin {

    @Shadow(remap = false)
    protected OptionsList list;

    @Inject(method = "addContents", at = @At("TAIL"), remap = false)
    private void crowmap$addConfigButton(CallbackInfo ci) {
        // shut, there is no issue
        if (!((Object) this instanceof ControlsScreen)) return;
        Button button = Button.builder(
            Component.translatable("crowmap.gui.open_config"),
            btn -> Minecraft.getInstance().setScreen(
                ConfigScreen.create(CrowmapConfig.INSTANCE.getWrapper(), (Screen)(Object) this)
            )
        ).build();
        list.addSmall(button, null);
    }
}
