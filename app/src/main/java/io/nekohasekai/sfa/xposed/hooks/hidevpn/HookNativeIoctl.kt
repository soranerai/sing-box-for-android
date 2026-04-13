package io.nekohasekai.sfa.xposed.hooks.hidevpn

import io.nekohasekai.sfa.xposed.HookErrorStore
import io.nekohasekai.sfa.xposed.hooks.XHook

class HookNativeIoctl(private val classLoader: ClassLoader) : XHook {
    
    // Объявляем нативный метод
    private external fun init()

    override fun injectHook() {
        try {
            // Загружаем библиотеку
            System.loadLibrary("hidevpn")
            
            // Вызываем нативный метод для установки Dobby хуков
            init()
            
            HookErrorStore.i("NativeHook", "Dobby getifaddrs hook injected successfully")
        } catch (e: Throwable) {
            HookErrorStore.e("NativeHook", "Failed to load native library or init hooks", e)
        }
    }
}