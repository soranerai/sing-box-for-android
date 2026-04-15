package io.nekohasekai.sfa.xposed.hooks.hidevpn

import io.nekohasekai.sfa.xposed.HookErrorStore
import io.nekohasekai.sfa.xposed.hooks.XHook

class HookNativeIoctl(private val classLoader: ClassLoader) : XHook {
    private external fun init()

    override fun injectHook() {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val getMethod = c.getMethod("get", String::class.java, String::class.java)
            val propValue = getMethod.invoke(null, "debug.sfa.lowlevel_hide", "0") as String
            val isEnabled = propValue == "1"
            HookErrorStore.i("NativeHook", "Property raw value: '$propValue', isEnabled: $isEnabled")

            if (!isEnabled) return

            System.loadLibrary("hidevpn")
            init()
        } catch (e: Throwable) {
            HookErrorStore.e("NativeHook", "Failed to read property", e)
        }
    }
}
