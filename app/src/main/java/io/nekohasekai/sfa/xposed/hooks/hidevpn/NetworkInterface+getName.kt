package io.nekohasekai.sfa.xposed.hooks.hidevpn

import de.robv.android.xposed.XposedHelpers
import io.nekohasekai.sfa.xposed.HookErrorStore
import io.nekohasekai.sfa.xposed.PrivilegeSettingsStore
import io.nekohasekai.sfa.xposed.hooks.SafeMethodHook
import io.nekohasekai.sfa.xposed.hooks.XHook
import io.nekohasekai.sfa.xposed.hooks.MtkUtils

class HookNetworkInterfaceGetName(private val classLoader: ClassLoader) : XHook {
    private companion object {
        private const val SOURCE = "HookNetworkInterfaceGetName"
    }

    override fun injectHook() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            hookJniGetNameApi33Plus()
        } else {
            hookJniGetNameLegacy()
        }
    }

    private fun hookJniGetNameApi33Plus() {
        val vpnClass = findVpnClass()
        val depsClass = XposedHelpers.findClass("${vpnClass.name}\$Dependencies", classLoader)
        XposedHelpers.findAndHookMethod(
            depsClass,
            "jniGetName",
            vpnClass,
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: MethodHookParam) {
                    processJniGetNameResult(param)
                }
            },
        )
        HookErrorStore.i(SOURCE, "Hooked ${depsClass.name}.jniGetName (API 33+)")
    }

    private fun hookJniGetNameLegacy() {
        val cls = findVpnClass()
        XposedHelpers.findAndHookMethod(
            cls,
            "jniGetName",
            Int::class.javaPrimitiveType,
            object : SafeMethodHook(SOURCE) {
                override fun afterHook(param: MethodHookParam) {
                    processJniGetNameResult(param)
                }
            },
        )
        HookErrorStore.i(SOURCE, "Hooked ${cls.name}.jniGetName (legacy)")
    }

    private fun processJniGetNameResult(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        val result = param.result
        if (result !is String) {
            if (result != null) {
                HookErrorStore.e(SOURCE, "jniGetName returned unexpected type: ${result.javaClass.name}")
            }
            return
        }
        if (!PrivilegeSettingsStore.shouldRenameInterface()) return
        if (!isTunInterface(result)) return
        
        val prefix = PrivilegeSettingsStore.interfacePrefix()
        val renamed = renameInterface(result, prefix) ?: return
        param.result = renamed
    }

    private fun findVpnClass(): Class<*> = XposedHelpers.findClass("com.android.server.connectivity.Vpn", classLoader)

    private fun isTunInterface(name: String): Boolean = name.startsWith("tun")

    private fun getFinalAvailableName(oldName: String, prefix: String, isMtkSwap: Boolean): String? {
        val oldIndex = MtkUtils.getInterfaceIndex(oldName)
        if (oldIndex <= 0) {
            HookErrorStore.e(SOURCE, "rename interface: old name not found (old=$oldName)")
            return null
        }

        if (isMtkSwap) {
            val nameForSwap = MtkUtils.findAvailableNameForSwap(prefix)
            if (nameForSwap == null) {
                HookErrorStore.e(SOURCE, "MTK Swap: no available DOWN interface for prefix $prefix; fallback ccmni21")
                return "ccmni21"
            }
            return nameForSwap
        } else {
            val availableName = MtkUtils.findAvailableName(prefix) ?: return null
            if (availableName == oldName) {
                return oldName
            }
            return availableName
        }
    }

    private fun renameInterface(oldName: String, prefix: String): String? {
        val oldIndex = MtkUtils.getInterfaceIndex(oldName)
        if (oldIndex <= 0) {
            HookErrorStore.e(SOURCE, "rename interface: source not found ($oldName)")
            return null
        }
        
        val isMtkSwap = prefix.trim() == "ccmni"
        val newName = getFinalAvailableName(oldName, prefix, isMtkSwap) ?: return null

        // MTK Swap logic
        if (isMtkSwap && newName != "ccmni21") {
            return MtkUtils.renameInterfaceWithSwap(oldName, oldIndex, newName)
        }

        // Standard rename
        if (!MtkUtils.renameWithNetlink(oldIndex, newName, removeP2p = isMtkSwap)) {
            HookErrorStore.e(SOURCE, "rename failed: $oldName -> $newName")
            return null
        }
        
        val newIndex = MtkUtils.getInterfaceIndex(newName)
        if (newIndex <= 0) {
            HookErrorStore.e(SOURCE, "rename interface: new name not found (old=$oldName index=$oldIndex)")
            return null
        }
        
        HookErrorStore.i(SOURCE, "rename interface: $oldName -> $newName")
        return newName
    }
}
