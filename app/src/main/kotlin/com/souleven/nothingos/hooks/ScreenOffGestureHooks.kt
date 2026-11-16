package com.souleven.nothingos.hooks

import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.souleven.nothingos.MainHook.Companion.TAG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.WeakHashMap

class ScreenOffGestureHooks : HookModule {

    companion object {
        const val KEY_ENABLED = "screen_off_gesture_guard"
        private const val LOG_TAG = "NothingTweaksGesture"
    }

    @Volatile private var centralSurfaces: Any? = null
    @Volatile private var lightReveal: Any? = null
    @Volatile private var powerInteractor: Any? = null

    // Window dispatch runs on the main thread. Finish dropping a rejected gesture even
    // if the animation ends before the finger lifts; never deliver an orphaned MOVE/UP.
    private val blockedGestures = WeakHashMap<View, Long>()

    override fun handleLoadPackage(lpparam: LoadPackageParam, prefs: Prefs) {
        Log.i(LOG_TAG, "Installing gesture diagnostic v2 (scene support) in ${lpparam.processName}")
        val centralClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            lpparam.classLoader
        )
        val revealClass = XposedHelpers.findClassIfExists(
            "com.nothing.systemui.keyguard.NTLightRevealScrimStartable",
            lpparam.classLoader
        )
        val windowClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shade.NotificationShadeWindowView",
            lpparam.classLoader
        )
        val triggersClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.doze.DozeTriggers",
            lpparam.classLoader
        )
        val sceneWindowClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.scene.ui.view.SceneWindowRootView",
            lpparam.classLoader
        )
        val powerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.power.domain.interactor.PowerInteractor",
            lpparam.classLoader
        )
        if (centralClass == null || revealClass == null || windowClass == null || triggersClass == null) {
            Log.e(LOG_TAG, "Missing classes: central=${centralClass != null}, reveal=${revealClass != null}, window=${windowClass != null}, triggers=${triggersClass != null}")
            XposedBridge.log("$TAG Screen-off gesture controllers unavailable")
            return
        }

        XposedBridge.hookAllConstructors(centralClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                centralSurfaces = param.thisObject
                Log.i(LOG_TAG, "CentralSurfaces captured")
            }
        })
        XposedBridge.hookAllConstructors(revealClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                lightReveal = param.thisObject
                Log.i(LOG_TAG, "LightReveal captured")
            }
        })

        if (powerClass != null) {
            XposedBridge.hookAllConstructors(powerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    powerInteractor = param.thisObject
                    Log.i(LOG_TAG, "PowerInteractor captured")
                }
            })
            XposedHelpers.findAndHookMethod(
                powerClass,
                "onStartedGoingToSleep",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        powerInteractor = param.thisObject
                        logState("scene sleep started", prefs)
                    }
                }
            )
        }

        val windowTouchHook = object : XC_MethodHook(10_000) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val event = param.args[0] as? MotionEvent ?: return
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    logState("${view.javaClass.simpleName} DOWN", prefs)
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    blockedGestures.remove(view)
                    return
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedGestures.remove(view)
                if (blockedGestures[view] != event.downTime && !shouldBlockGestures(prefs)) return

                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    Log.i(LOG_TAG, "Blocked ${view.javaClass.simpleName} gesture")
                }

                blockedGestures[view] = event.downTime
                if (event.actionMasked == MotionEvent.ACTION_UP) blockedGestures.remove(view)
                param.result = true
            }
        }
        for (window in listOfNotNull(windowClass, sceneWindowClass)) {
            XposedHelpers.findAndHookMethod(window, "dispatchTouchEvent", MotionEvent::class.java, windowTouchHook)
        }

        XposedHelpers.findAndHookMethod(
            centralClass,
            "shouldIgnoreTouch",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (shouldBlockGestures(prefs)) param.result = true
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            centralClass,
            "updateNotificationPanelTouchState",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!shouldBlockGestures(prefs)) return
                    val window = XposedHelpers.callMethod(
                        param.thisObject,
                        "getNotificationShadeWindowViewController"
                    ) ?: return
                    XposedHelpers.callMethod(window, "cancelCurrentTouch")
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            triggersClass,
            "onSensor",
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            FloatArray::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val reason = param.args[0] as? Int ?: return
                    // Only touchscreen tap/double-tap wake signals. Fingerprint events use
                    // their own overlay and callbacks, which this hook never intercepts.
                    if ((reason == 4 || reason == 9) && shouldBlockGestures(prefs)) {
                        logState("blocked tap sensor $reason", prefs)
                        param.result = null
                    } else if (reason == 4 || reason == 9) {
                        logState("allowed tap sensor $reason", prefs)
                    }
                }
            }
        )

        val wakefulnessClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.keyguard.WakefulnessLifecycle",
            lpparam.classLoader
        )
        if (wakefulnessClass != null) {
            XposedHelpers.findAndHookMethod(
                wakefulnessClass,
                "setWakefulness",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logState("wakefulness=${param.args[0]}", prefs)
                    }
                }
            )
        }
        prefs.addListener { logState("preferences updated", prefs) }
        Log.i(LOG_TAG, "Gesture diagnostic v2 hooks installed; scene=${sceneWindowClass != null}")
    }

    private fun logState(event: String, prefs: Prefs) {
        try {
            val central = centralSurfaces
            val reveal = lightReveal
            val awake = central?.let { XposedHelpers.callMethod(it, "isWakingOrAwake") }
            val sleeping = central?.let { XposedHelpers.callMethod(it, "isGoingToSleep") }
            val animating = reveal?.let { XposedHelpers.callMethod(it, "isAnimationPlaying") }
            Log.i(
                LOG_TAG,
                "$event: enabled=${prefs.getBoolean(KEY_ENABLED, false)}, " +
                    "central=${central != null}, reveal=${reveal != null}, " +
                    "awake=$awake, sleeping=$sleeping, animating=$animating, " +
                    "sceneWakefulness=${sceneWakefulness()}"
            )
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "$event: state query failed", t)
        }
    }

    private fun shouldBlockGestures(prefs: Prefs): Boolean {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return false
        val sceneState = sceneWakefulness()
        if (sceneState != null) {
            return when (sceneState) {
                "STARTING_TO_SLEEP" -> true
                "ASLEEP" -> lightReveal?.let { XposedHelpers.callMethod(it, "isAnimationPlaying") == true } ?: false
                else -> false
            }
        }
        val central = centralSurfaces ?: return false
        // Power-button wake and fingerprint wake must take effect immediately, even
        // if a reveal animation is still running. Ordinary AOD gestures resume at rest.
        if (XposedHelpers.callMethod(central, "isWakingOrAwake") == true) return false
        if (XposedHelpers.callMethod(central, "isGoingToSleep") == true) return true
        val reveal = lightReveal ?: return false
        return XposedHelpers.callMethod(reveal, "isAnimationPlaying") == true
    }

    private fun sceneWakefulness(): String? {
        val power = powerInteractor ?: return null
        val flow = XposedHelpers.callMethod(power, "getDetailedWakefulness") ?: return null
        val model = XposedHelpers.callMethod(flow, "getValue") ?: return null
        return (XposedHelpers.callMethod(model, "getInternalWakefulnessState") as? Enum<*>)?.name
    }
}
