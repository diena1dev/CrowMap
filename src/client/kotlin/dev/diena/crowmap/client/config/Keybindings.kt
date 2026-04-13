package dev.diena.crowmap.client.config

import com.mojang.blaze3d.platform.InputConstants
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.features.world.SignAnchorTracker
import dev.diena.crowmap.client.features.world.WorldProjectionScreen
import dev.diena.crowmap.client.screen.BrowserScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

class Keybindings {
    private val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath(CrowmapClient.namespace, "keybindings"))

    fun init() {
        val openMap = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "Open Map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                CATEGORY
            )
        )

        val placeProjection = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "Place World Map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_GRAVE_ACCENT,
                CATEGORY
            )
        )

        val toggleProjection = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "Toggle World Map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                CATEGORY
            )
        )

        val toggleHud = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "Toggle HUD Map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                CATEGORY
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            while (openMap.consumeClick()) {
                CrowmapClient.mc.setScreen(BrowserScreen())
            }

            while (placeProjection.consumeClick()) {
                if (CrowmapConfig.anchorEnabled) {
                    SignAnchorTracker.storeOffsetFromPlayer()
                } else {
                    WorldProjectionScreen.setPositionFromPlayer()
                }
                CrowmapConfig.save()
            }

            while (toggleProjection.consumeClick()) {
                WorldProjectionScreen.toggle()
                CrowmapConfig.save()
            }

            while (toggleHud.consumeClick()) {
                CrowmapConfig.hudEnabled = !CrowmapConfig.hudEnabled
                CrowmapConfig.save()
            }
        }
    }
}