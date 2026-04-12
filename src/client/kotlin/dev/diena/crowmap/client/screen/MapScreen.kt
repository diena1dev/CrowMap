package dev.diena.crowmap.client.screen

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.OwoUIAdapter
import net.minecraft.client.gui.GuiGraphics


class MapScreen: BaseOwoScreen<FlowLayout>() {
    override fun createAdapter(): OwoUIAdapter<FlowLayout?> {
        return OwoUIAdapter.create(this, UIContainers::verticalFlow);
    }

    override fun build(p0: FlowLayout?) {}

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
    }
}