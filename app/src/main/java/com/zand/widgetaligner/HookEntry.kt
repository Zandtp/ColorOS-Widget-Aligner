package com.zand.widgetaligner

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
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

    // 默认兜底圆角（ColorOS 14/15 大卡片标准圆角通常在 28dp ~ 30dp 左右）
    private var detectedCornerRadiusPx: Float = -1f
    private val FALLBACK_CORNER_RADIUS_DP = 28f

    // 垂直微调（截图里 14f 已经让底边完美齐平，保持不变）
    private val VERTICAL_OFFSET_DP = 14f

    // 水平内缩补偿（让多邻国左右边缘与上方「天气时钟」严格对齐）
    private val HORIZONTAL_INSET_DP = 4f

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.launcher") return

        // 1. 清除系统强制追加的默认 Padding
        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "getDefaultPaddingForWidget",
            Context::class.java,
            ComponentName::class.java,
            Rect::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
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
                    if (param.thisObject is AppWidgetHostView) {
                        param.args[0] = 0; param.args[1] = 0; param.args[2] = 0; param.args[3] = 0
                    }
                }
            }
        )

        // 2. 探测原生卡片的圆角与尺寸特征
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setOutlineProvider",
            ViewOutlineProvider::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (view is AppWidgetHostView) return
                    val clsName = view.javaClass.name
                    // 筛选桌面原生卡片或大文件夹容器
                    if (clsName.contains("Card", ignoreCase = true) ||
                        clsName.contains("Widget", ignoreCase = true) ||
                        clsName.contains("Folder", ignoreCase = true)
                    ) {
                        view.post {
                            probeNativeViewMetrics(view)
                        }
                    }
                }
            }
        )

        // 3. 在布局完成 (onLayout) 时应用精确尺寸与圆角，避免 width/height 为 0
        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "onLayout",
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val hostView = param.thisObject as? AppWidgetHostView ?: return
                    applyAlignedStyle(hostView)
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "updateAppWidget",
            RemoteViews::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val hostView = param.thisObject as? AppWidgetHostView ?: return
                    applyAlignedStyle(hostView)
                }
            }
        )
    }

    /**
     * 测量 ColorOS 原生卡片/文件夹的真实宽高与 Outline 圆角半径
     */
    private fun probeNativeViewMetrics(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        try {
            val outline = Outline()
            view.outlineProvider?.getOutline(view, outline)
            val rect = Rect()
            if (outline.getRect(rect)) {
                val radius = outline.radius
                if (radius > 10f && detectedCornerRadiusPx != radius) {
                    detectedCornerRadiusPx = radius
                    val dp = radius / view.context.resources.displayMetrics.density
                    XposedBridge.log("[WidgetAligner] 探测到原生卡片(${view.javaClass.simpleName}): 宽=${view.width}px, 高=${view.height}px, 圆角=${radius}px (${String.format("%.1f", dp)}dp)")
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyAlignedStyle(hostView: AppWidgetHostView) {
        val context = hostView.context ?: return
        val w = hostView.width
        val h = hostView.height
        if (w <= 0 || h <= 0) return

        // 优先使用探测到的原生圆角，未探测到则使用 28dp 兜底
        val radiusPx = if (detectedCornerRadiusPx > 0f) {
            detectedCornerRadiusPx
        } else {
            dp2px(context, FALLBACK_CORNER_RADIUS_DP)
        }

        // 左右微缩以匹配上方原生小卡片的左右边距
        val insetX = dp2px(context, HORIZONTAL_INSET_DP).toInt()

        hostView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(insetX, 0, view.width - insetX, view.height, radiusPx)
            }
        }
        hostView.clipToOutline = true

        if (VERTICAL_OFFSET_DP != 0f) {
            hostView.translationY = dp2px(context, VERTICAL_OFFSET_DP)
        }

        if (hostView.childCount > 0) {
            val child = hostView.getChildAt(0)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && (lp.leftMargin != insetX || lp.topMargin != 0 || lp.rightMargin != insetX || lp.bottomMargin != 0)) {
                lp.setMargins(insetX, 0, insetX, 0)
                child.layoutParams = lp
            }
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
