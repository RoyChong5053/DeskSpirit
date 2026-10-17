package com.roychong.deskspirit.render

import androidx.annotation.StringRes
import com.roychong.deskspirit.R

/**
 * 运行时圆球的外观 preset。
 *
 * [id] 存进 `AudioSettings`，重启后恢复；[labelRes]/[descRes] 供设置页展示。
 */
enum class SphereStyle(
    val id: Int,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
) {
    /** 玻璃球 + 体积云（初版观感）。 */
    AURORA(0, R.string.style_aurora, R.string.style_aurora_desc),

    /** 呼应 logo：球外三圈不同倾角的发光轨道。 */
    ORBIT(1, R.string.style_orbit, R.string.style_orbit_desc),

    /** 内部等离子对流，高频触发边缘电弧。 */
    PLASMA(2, R.string.style_plasma, R.string.style_plasma_desc),

    /** 经纬线 + 扫描线，科技感、开销低。 */
    WIREFRAME(3, R.string.style_wireframe, R.string.style_wireframe_desc),

    /** 液面涟漪，低频激起大波。 */
    RIPPLE(4, R.string.style_ripple, R.string.style_ripple_desc),

    /** 极简呼吸光晕，最省电，适合长时间挂机。 */
    BREATH(5, R.string.style_breath, R.string.style_breath_desc);

    companion object {
        fun of(id: Int): SphereStyle = entries.firstOrNull { it.id == id } ?: AURORA
    }
}
