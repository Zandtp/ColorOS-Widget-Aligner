package com.zand.widgetaligner

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    companion object {
        private val WEATHER_PACKAGES = setOf(
            "com.coloros.weather",
            "com.coloros.weather2",
            "com.heytap.weather",
            "com.oppo.weather"
        )

        private const val PREFS_NAME = "zand_widget_anchor_prefs"
        private const val KEY_RADIUS_PX = "anchor_radius_px"
        private const val MIN_VIEW_SIZE_PX = 50
        private const val MIN_RADIUS_PX = 1f
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ColorOS/OPPO 启动器可能使用其他包名；需要按目标设备确认。
        if (lpparam.packageName != "com.android.launcher") return

        // 保留系统返回值。第三方组件的默认 padding 可能是其布局的一部分。
        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "getDefaultPaddingForWidget",
            Context::class.java,
            ComponentName::class.java,
            Rect::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val component = param.args.getOrNull(1) as? ComponentName ?: return
                    if (component.packageName !in WEATHER_PACKAGES) return

                    val original = param.result as? Rect ?: return
                    param.result = Rect(original)
                }
            }
        )

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val host = param.thisObject as? AppWidgetHostView ?: return
                if (isWeatherAnchor(host)) {
                    ensurePrefs(host.context)
                    host.post { captureWeatherRadius(host) }
                } else {
                    ensurePrefs(host.context)
                    host.post { applyWeatherRadiusIfKnown(host) }
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
            hook
        )

        XposedHelpers.findAndHookMethod(
            AppWidgetHostView::class.java,
            "updateAppWidget",
            android.widget.RemoteViews::class.java,
            hook
        )
    }

    private fun ensurePrefs(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun isWeatherAnchor(host: AppWidgetHostView): Boolean {
        val packageName = host.appWidgetInfo?.provider?.packageName ?: return false
        return packageName in WEATHER_PACKAGES
    }

    /**
     * 仅记录可以从 View Outline 或 GradientDrawable 可靠读到的圆角。
     * View 的可见边界可能由图片、自绘 Canvas 或非矩形路径实现，
     * 这些情况无法通过通用 View API 准确推断。
     */
    private fun captureWeatherRadius(weatherHost: AppWidgetHostView) {
        if (weatherHost.width < MIN_VIEW_SIZE_PX || weatherHost.height < MIN_VIEW_SIZE_PX) return

        val card = findRoundedCard(weatherHost) ?: return
        val radius = extractRadiusPx(card) ?: return
        if (radius < MIN_RADIUS_PX) return

        prefs?.edit()
            ?.putFloat(KEY_RADIUS_PX, radius)
            ?.apply()

        XposedBridge.log(
            "[WidgetAligner] Captured weather radius=${radius}px " +
                "view=${card.javaClass.name} size=${card.width}x${card.height}"
        )
    }

    /**
     * 圆角只作用于可识别的第三方背景 View；不设置 HostView 的裁剪，
     * 不改 HostView/子 View 的尺寸、padding、margin 或 translation。
     */
    private fun applyWeatherRadiusIfKnown(host: AppWidgetHostView) {
        if (host.width < MIN_VIEW_SIZE_PX || host.height < MIN_VIEW_SIZE_PX) return
        val radius = prefs?.getFloat(KEY_RADIUS_PX, -1f) ?: return
        if (radius < MIN_RADIUS_PX) return

        val card = findBackgroundCard(host) ?: return

        // 保留原 Outline 行为，仅当该 View 原本有可识别圆角时才替换其半径。
        val existingRadius = extractRadiusPx(card) ?: return
        if (existingRadius < MIN_RADIUS_PX) return

        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val rect = drawableBounds(view)
                if (rect.width() <= 0 || rect.height() <= 0) {
                    outline.setEmpty()
                    return
                }
                outline.setRoundRect(rect, radius.coerceAtMost(
                    minOf(rect.width(), rect.height()) / 2f
                ))
            }
        }
        card.invalidateOutline()
    }

    private fun findRoundedCard(root: View): View? {
        if (root.width >= MIN_VIEW_SIZE_PX &&
            root.height >= MIN_VIEW_SIZE_PX &&
            extractRadiusPx(root) != null
        ) {
            return root
        }

        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findRoundedCard(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findBackgroundCard(root: View): View? {
        if (root !== root.rootView &&
            root.width >= MIN_VIEW_SIZE_PX &&
            root.height >= MIN_VIEW_SIZE_PX &&
            root.background != null &&
            extractRadiusPx(root) != null
        ) {
            return root
        }

        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findBackgroundCard(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun extractRadiusPx(view: View): Float? {
        val background = view.background
        if (background is GradientDrawable) {
            val radii = background.cornerRadii
            if (radii != null && radii.isNotEmpty()) {
                val first = radii[0]
                if (first > 0f) return first
            }
            val radius = background.cornerRadius
            if (radius > 0f) return radius
        }

        return try {
            val provider = view.outlineProvider ?: return null
            val outline = Outline()
            provider.getOutline(view, outline)
            if (outline.radius > 0f) outline.radius else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableBounds(view: View): Rect {
        val bg = view.background ?: return Rect(0, 0, view.width, view.height)
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = (view.width - view.paddingRight).coerceAtLeast(left)
        val bottom = (view.height - view.paddingBottom).coerceAtLeast(top)
        return Rect(left, top, right, bottom)
    }
}
