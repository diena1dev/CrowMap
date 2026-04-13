package dev.diena.crowmap.client.screen

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * A transparent owo-ui screen whose sole purpose is to host a [ContextPopup]
 * when triggered from the in-world projection (which has no owo-ui screen of its own).
 *
 * The popup opens at the centre of the screen and auto-closes when the player
 * clicks outside or presses Escape.
 */
class ContextPopupScreen(
    private val browserPixelX: Int,
    private val browserPixelY: Int
) : BaseOwoScreen<FlowLayout>(Component.literal("CrowMap Context")) {

    override fun createAdapter(): OwoUIAdapter<FlowLayout> {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow)
    }

    override fun build(rootComponent: FlowLayout) {
        rootComponent.surface(Surface.BLANK)
    }

    override fun init() {
        super.init()

        // Open the context popup centred on screen
        ContextPopup.open(
            screen = this,
            root = uiAdapter.rootComponent,
            browserPixelX = browserPixelX,
            browserPixelY = browserPixelY,
            guiX = width / 2.0,
            guiY = height / 2.0,
            onClose = { onClose() }
        )
    }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        // Let owo-ui handle the click first (dropdown buttons, etc.).
        // Note: openContextMenu's beforeMouseClick already fires before this,
        // so if the click was outside the dropdown it has already been removed.
        val handled = super.mouseClicked(event, isDoubleClick)
        if (!handled) {
            // Click was outside every UI element (or popup hasn't loaded yet) → close
            onClose()
        }
        return true
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        // Semi-transparent backdrop
        context.fill(0, 0, width, height, 0x66000000)
        super.render(context, mouseX, mouseY, delta)
    }

    override fun isPauseScreen(): Boolean = false
}

