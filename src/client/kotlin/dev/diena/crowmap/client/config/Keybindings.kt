package dev.diena.crowmap.client.config

import com.mojang.blaze3d.platform.InputConstants
import dev.diena.crowmap.client.CrowmapClient
import dev.diena.crowmap.client.features.hud.OverlayHud
import dev.diena.crowmap.client.features.world.SignAnchorTracker
import dev.diena.crowmap.client.features.world.WorldProjectionScreen
import dev.diena.crowmap.client.screen.BrowserScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

class Keybindings {

    companion object {
        /** Exposed so OverlayHud can check isDown for HOLD mode. */
        var showOverlay: KeyMapping? = null
    }

    private val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath(CrowmapClient.namespace, "keybindings"))

    fun init() {
        val openMap = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.crowmap.open_map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                CATEGORY
            )
        )

        val placeProjection = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.crowmap.place_projection",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_GRAVE_ACCENT,
                CATEGORY
            )
        )

        val toggleProjection = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.crowmap.toggle_projection",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                CATEGORY
            )
        )

        val toggleHud = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.crowmap.toggle_hud",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                CATEGORY
            )
        )

        val overlayKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.crowmap.show_overlay",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                CATEGORY
            )
        )
        showOverlay = overlayKey

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

            // Drain click queue in all modes; only act in TOGGLE mode.
            var overlayClicked = false
            while (overlayKey.consumeClick()) overlayClicked = true
            if (overlayClicked && CrowmapConfig.overlayMode == CrowmapConfigModel.OverlayMode.TOGGLE) {
                OverlayHud.visible = !OverlayHud.visible
            }
        }
    }
}