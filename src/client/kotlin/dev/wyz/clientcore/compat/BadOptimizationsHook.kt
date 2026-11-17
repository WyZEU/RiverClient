package dev.wyz.clientcore.compat

import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.TimeChangerModule
import net.minecraft.client.Minecraft
import java.util.function.BooleanSupplier

/** Invalidates cached lighting only when River changes brightness or visual time. */
class BadOptimizationsHook : BooleanSupplier {
    private var gamma = Double.NaN
    private var time: Long? = null
    private var level: Any? = null

    override fun getAsBoolean(): Boolean {
        val client = Minecraft.getInstance()
        val nextGamma = client.options.gamma().get()
        val nextTime = ModuleRegistry.get<TimeChangerModule>("time_changer")?.overrideDayTime()
        val changed = gamma != nextGamma || time != nextTime || level !== client.level
        gamma = nextGamma
        time = nextTime
        level = client.level
        return changed
    }
}
