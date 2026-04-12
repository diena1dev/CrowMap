package dev.diena.crowmap.client.config

import com.mojang.blaze3d.platform.InputConstants
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.features.world.WorldProjectionScreen
import dev.diena.crowmap.client.screen.BrowserScreen
import dev.diena.crowmap.client.screen.MapScreen
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
                "Place Projection",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                CATEGORY
            )
        )

        val toggleProjection = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "Toggle Projection",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                CATEGORY
            )
        )

        val toggleHud = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "Toggle HUD",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                CATEGORY
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            while (openMap.consumeClick()) {
                CrowmapClient.mc.setScreen(BrowserScreen())
            }

            while (placeProjection.consumeClick()) {
                WorldProjectionScreen.setPositionFromPlayer()
            }

            while (toggleProjection.consumeClick()) {
                WorldProjectionScreen.toggle()
            }

            while (toggleHud.consumeClick()) {
                CrowmapConfig.hudEnabled = !CrowmapConfig.hudEnabled
            }
        }
    }
}