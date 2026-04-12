package io.nekohasekai.sfa.xposed.hooks.hidevpn

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.nekohasekai.sfa.xposed.hooks.XHook
import io.nekohasekai.sfa.xposed.HookErrorStore
import java.net.NetworkInterface

class HookNetworkInterfaceGetFlags(private val classLoader: ClassLoader) : XHook {
    private companion object {
        private const val SOURCE = "HookNetworkInterfaceGetFlags"
        private const val IFF_POINTOPOINT = 0x10 // Флаг Point-to-Point
    }

    override fun injectHook() {
        try {
            // Хукаем стандартный Java метод получения флагов
            XposedHelpers.findAndHookMethod(
                NetworkInterface::class.java,
                "getFlags",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ni = param.thisObject as? NetworkInterface ?: return
                        val name = ni.name ?: return
                        var flags = param.result as Int

                        // remove pointpoint flag
                        if (name == "tun0" || name.startsWith("ccmni")) {
                            if ((flags and IFF_POINTOPOINT) != 0) {
                                flags = flags and IFF_POINTOPOINT.inv()
                                param.result = flags
                                // Можно раскомментировать для отладки:
                                HookErrorStore.i(SOURCE, "Stripped P2P from $name")
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            io.nekohasekai.sfa.xposed.HookErrorStore.e(SOURCE, "Failed to hook NetworkInterface.getFlags", e)
        }
    }
}