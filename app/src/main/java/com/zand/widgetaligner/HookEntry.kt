package com.zand.widgetaligner

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.RemoteViews
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    // OPPO/ColorOS 系统天气常见包名列表
    private val WEATHER_PACKAGES = setOf(
        "com.coloros.weather",
        "com.coloros.weather2",
        "com.heytap.weather",
        "com.oppo.weather"
    )

    private var prefs: SharedPreferences? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.launcher") return

        // 1. 清除第三方小组件的默认系统 Padding（保留天气小组件自身原始结构作为锚点标尺）
        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "getDefaultPaddingForWidget",
            Context::class.java,
            ComponentName::class.java,
            Rect::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cn = param.args[1] as? ComponentName
                    if (cn != null && cn.packageName in WEATHER_PACKAGES) return
                    param.result = Rect(0, 0, 0, 0)
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setPadding",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val host = param.thisObject as? AppWidgetHostView ?: return
                    if (isWeatherAnchor(host)) return
                    param.args[0] = 0; param.args[1] = 0; param.args[2] = 0; param.args[3] = 0
                }
            }
        )

        // 2. 在 AppWidgetHostView 布局完成时，区分是“天气锚点”还是“第三方小组件”
        val layoutHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val hostView = param.thisObject as? AppWidgetHostView ?: return
                ensurePrefs(hostView.context)

                if (isWeatherAnchor(hostView)) {
                    // 发现系统天气小组件：提取它的真实视觉边界与圆角，写入永久缓存
                    hostView.post { captureWeatherAnchor(hostView) }
                } else {
                    // 第三方小组件：从缓存读取天气标准尺寸进行规范化
                    applyWeatherStandard(hostView)
                }
            }
        }

        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "onLayout",
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            layoutHook
        )

        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "updateAppWidget",
            RemoteViews::class.java,
            layoutHook
        )
    }

    private fun ensurePrefs(context: Context?) {
        if (prefs == null && context != null) {
            prefs = context.getSharedPreferences("zand_widget_anchor_prefs", Context.MODE_PRIVATE)
        }
    }

    private fun isWeatherAnchor(hostView: AppWidgetHostView): Boolean {
        val pkg = hostView.appWidgetInfo?.provider?.packageName ?: return false
        return pkg in WEATHER_PACKAGES
    }

    /**
     * 测量系统天气小组件的真实可视边界与圆角，并保存为全桌面通用标尺
     */
    private fun captureWeatherAnchor(weatherView: AppWidgetHostView) {
        val cellW = weatherView.width
        val cellH = weatherView.height
        if (cellW <= 50 || cellH <= 50) return

        // 寻找天气小组件内部真正绘制背景圆角的卡片视图
        val cardView = findRoundedCardChild(weatherView) ?: weatherView
        val actualW = cardView.width
        val actualH = cardView.height
        if (actualW <= 50 || actualH <= 50) return

        // 提取圆角半径
        var radius = extractOutlineRadius(cardView)
        if (radius <= 5f) {
            radius = dp2px(weatherView.context, 24f)
        }

        // 识别当前天气小组件属于哪种网格规格 (2x1, 2x2, 4x1, 4x2, 4x4)
        val specKey = classifyGridSpec(weatherView.context, cellW, cellH)
        val editor = prefs?.edit() ?: return

        editor.putFloat("anchor_radius_px", radius)
        editor.putInt("${specKey}_w", actualW)
        editor.putInt("${specKey}_h", actualH)

        // 记录单格基准尺寸，用于自动推算其他未摆放过的规格 (如 4x1, 4x2, 4x4)
        val cols = if (specKey.startsWith("4")) 4 else 2
        val rows = specKey.substringAfter("x").toIntOrNull() ?: 1
        val singleColW = actualW / cols
        val singleRowH = actualH / rows

        // 推算并补全缺失的规格（仅当该规格未被实测覆盖时）
        val allSpecs = listOf("2x1", "2x2", "4x1", "4x2", "4x4")
        for (s in allSpecs) {
            if (s == specKey) continue
            val c = if (s.startsWith("4")) 4 else 2
            val r = s.substringAfter("x").toIntOrNull() ?: 1
            if (!prefs!!.contains("${s}_w")) {
                // 4列宽度约为2列宽度的2倍加上中间列间距
                val estW = if (c == cols) actualW else (actualW * c / cols)
                val estH = if (r == rows) actualH else (actualH * r / rows)
                editor.putInt("${s}_w", estW)
                editor.putInt("${s}_h", estH)
            }
        }
        editor.apply()

        XposedBridge.log("[WidgetAligner] 已采集天气锚点 [$specKey]: 实测宽=${actualW}px, 实测高=${actualH}px, 圆角=${radius}px")
    }

    /**
     * 将缓存的天气锚点尺寸应用到所有第三方小组件上
     */
    private fun applyWeatherStandard(hostView: AppWidgetHostView) {
        val context = hostView.context ?: return
        val cellW = hostView.width
        val cellH = hostView.height
        if (cellW <= 0 || cellH <= 0) return

        val specKey = classifyGridSpec(context, cellW, cellH)
        val p = prefs

        val radiusPx = p?.getFloat("anchor_radius_px", -1f)?.takeIf { it > 5f }
            ?: dp2px(context, 24f)

        val targetW = p?.getInt("${specKey}_w", -1)?.takeIf { it > 50 }
            ?: (cellW - dp2px(context, 10f).toInt())

        val targetH = p?.getInt("${specKey}_h", -1)?.takeIf { it > 50 }
            ?: (cellH - dp2px(context, 18f).toInt())

        val left = ((cellW - targetW) / 2).coerceAtLeast(0)
        val right = (left + targetW).coerceAtMost(cellW)
        val top = 0
        val bottom = targetH.coerceAtMost(cellH)

        hostView.translationY = 0f
        hostView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(left, top, right, bottom, radiusPx)
            }
        }
        hostView.clipToOutline = true

        // 同步调整第三方小组件内部子视图的边距，保证内容完整居中不溢出
        if (hostView.childCount > 0) {
            val child = hostView.getChildAt(0)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            val marginBottom = (cellH - bottom).coerceAtLeast(0)
            if (lp != null && (lp.leftMargin != left || lp.rightMargin != left || lp.bottomMargin != marginBottom || lp.topMargin != 0)) {
                lp.setMargins(left, 0, left, marginBottom)
                child.layoutParams = lp
            }
        }
    }

    /**
     * 根据小组件在桌面占据的像素宽高，判断其所属的网格规格 (2x1 / 2x2 / 4x1 / 4x2 / 4x4)
     */
    private fun classifyGridSpec(context: Context, w: Int, h: Int): String {
        val screenW = context.resources.displayMetrics.widthPixels
        val is4Col = w > screenW * 0.65f
        val colStr = if (is4Col) "4" else "2"

        val ratio = w.toFloat() / h.toFloat()
        val rowStr = when {
            is4Col && ratio > 2.8f -> "1"   // 4x1
            is4Col && ratio > 1.4f -> "2"   // 4x2
            is4Col -> "4"                   // 4x4
            !is4Col && ratio > 1.35f -> "1" // 2x1
            else -> "2"                     // 2x2
        }
        return "${colStr}x${rowStr}"
    }

    private fun findRoundedCardChild(root: View): View? {
        if (extractOutlineRadius(root) > 5f) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findRoundedCardChild(root.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun extractOutlineRadius(view: View): Float {
        return try {
            val outline = Outline()
            view.outlineProvider?.getOutline(view, outline)
            outline.radius
        } catch (_: Throwable) {
            -1f
        }
    }

    private fun dp2px(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}
