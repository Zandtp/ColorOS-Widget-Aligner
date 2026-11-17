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
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    private val TARGET_CORNER_RADIUS_DP = 22f
    private val VERTICAL_OFFSET_DP = 14f

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.launcher") return

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

        val styleHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val hostView = param.thisObject as? AppWidgetHostView ?: return
                applyStyle(hostView)
            }
        }

        XposedHelpers.findAndHookMethod(AppWidgetHostView::class.java, "onAttachedToWindow", styleHook)
        XposedHelpers.findAndHookMethod(AppWidgetHostView::class.java, "updateAppWidget", RemoteViews::class.java, styleHook)
    }

    private fun applyStyle(hostView: AppWidgetHostView) {
        val context = hostView.context ?: return
        val radiusPx = dp2px(context, TARGET_CORNER_RADIUS_DP)
        hostView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        hostView.clipToOutline = true
        if (VERTICAL_OFFSET_DP != 0f) {
            hostView.translationY = dp2px(context, VERTICAL_OFFSET_DP)
        }
        if (hostView.childCount > 0) {
            val child = hostView.getChildAt(0)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && (lp.leftMargin != 0 || lp.topMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0)) {
                lp.setMargins(0, 0, 0, 0)
                child.layoutParams = lp
            }
        }
    }

    private fun dp2px(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
    }
}
