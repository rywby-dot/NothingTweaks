package com.souleven.nothingos.hooks

import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class ScreenOffFingerprintHooks : HookModule {

    // Doze transitions and fingerprint UI requests run on the SystemUI main thread.
    private var suppressAutomaticShow = false

    companion object {
        const val KEY_HIDE_SCREEN_OFF_ICON = "fp_hide_screen_off_icon"

        // Automatic screen-off requests in Nothing OS C5.0-260924-2115.
        // Touch, notification, tap-to-show and waking requests remain unchanged.
        private val SCREEN_OFF_REASONS = setOf(
            "SCREEN_OFF_ANIMATION_END",
            "SCREEN_BRIGHTNESS_INITIALIZED",
            "SCREEN_CHANGE_TO_DOZE",
            "DOZE_AOD,ANIMATOR_DURATION_SCALE=0",
            "KEYGUARD_VISIBLE_WHILE_DOZING",
            "show FOD when state changed"
        )
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        val controller = XposedHelpers.findClassIfExists(
            "com.android.systemui.biometrics.UdfpsController",
            lpparam.classLoader
        )
        val triggers = XposedHelpers.findClassIfExists(
            "com.nothing.systemui.doze.DozeTriggersEx",
            lpparam.classLoader
        )
        if (controller == null || triggers == null) {
            XposedBridge.log("$TAG Screen-off fingerprint controller unavailable")
            return
        }

        XposedHelpers.findAndHookMethod(
            controller,
            "showFingerprintIcon",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(KEY_HIDE_SCREEN_OFF_ICON, false)) return
                    val reason = param.args[0] as? String ?: return
                    if (reason !in SCREEN_OFF_REASONS ||
                        XposedHelpers.getBooleanField(param.thisObject, "mIsDeviceInteractive") ||
                        XposedHelpers.getBooleanField(param.thisObject, "mOnFingerDown")) {
                        // A tap, touch, notification or wake ends the initial screen-off window.
                        suppressAutomaticShow = false
                        return
                    }
                    if (!suppressAutomaticShow) return

                    // Keep the invisible sensor touchable, just as the stock timeout does.
                    XposedHelpers.callMethod(
                        param.thisObject,
                        "dismissFingerprintIcon",
                        "NothingTweaks: screen off",
                        true
                    )
                    param.result = null
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            triggers,
            "transitionTo",
            "com.android.systemui.doze.DozeMachine\$State",
            "com.android.systemui.doze.DozeMachine\$State",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!prefs.getBoolean(KEY_HIDE_SCREEN_OFF_ICON, false)) return
                    if ((param.args[1] as? Enum<*>)?.name != "INITIALIZED") return
                    suppressAutomaticShow = true

                    // Hide an icon carried over from the lockscreen before the doze UI appears.
                    val authController = XposedHelpers.getObjectField(param.thisObject, "mAuthController")
                    XposedHelpers.callMethod(
                        authController,
                        "dismissFingerprintIcon",
                        "NothingTweaks: screen off",
                        true
                    )
                }
            }
        )
    }
}
