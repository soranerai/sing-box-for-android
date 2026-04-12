package io.nekohasekai.sfa.xposed.hooks.hidevpn

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.nekohasekai.sfa.xposed.HookErrorStore
import io.nekohasekai.sfa.xposed.hooks.XHook
import java.io.InputStream

class HookProcessGetInputStream(private val classLoader: ClassLoader) : XHook {

    private companion object {
        private const val SOURCE = "HookProcessGetInputStream"
        private const val TARGET_FLAG = "POINTOPOINT"
        // Заменяем на пробелы той же длины, чтобы не ломать парсинг по колонкам
        private const val REPLACEMENT = "          " 

        // Флаг для текущего потока: нужно ли фильтровать следующий InputStream
        private val shouldFilterStream = ThreadLocal.withInitial { false }
    }

    override fun injectHook() {
        try {
            // 1. Хукаем запуск процесса
            XposedHelpers.findAndHookMethod(
                ProcessBuilder::class.java,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pb = param.thisObject as ProcessBuilder
                        val command = pb.command().joinToString(" ").lowercase()

                        // Проверяем, является ли команда сетевой утилитой
                        if (command.contains("ip ") || 
                            command.contains("ifconfig") || 
                            command.contains("netcfg")) {
                            
                            shouldFilterStream.set(true)
                            // HookErrorStore.i(SOURCE, "Detected network command: $command")
                        }
                    }
                }
            )

            // 2. Хукаем получение потока вывода
            val processClass = Class.forName("java.lang.Process")
            XposedHelpers.findAndHookMethod(
                processClass,
                "getInputStream",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Если команда не была сетевой, ничего не делаем
                        if (!shouldFilterStream.get()) return
                        
                        // Сбрасываем флаг, чтобы не задеть другие потоки случайно
                        shouldFilterStream.set(false)

                        val originalStream = param.result as? InputStream ?: return
                        if (originalStream is P2PFilterInputStream) return

                        // Оборачиваем поток в наш фильтр
                        param.result = P2PFilterInputStream(originalStream)
                        // HookErrorStore.i(SOURCE, "Output stream wrapped in P2P filter")
                    }
                }
            )

        } catch (e: Throwable) {
            HookErrorStore.e(SOURCE, "Failed to inject shell filter hooks", e)
        }
    }

    /**
     * Внутренний класс-фильтр, который налету меняет байты в потоке.
     */
    private class P2PFilterInputStream(private val inner: InputStream) : InputStream() {
        
        override fun read(): Int {
            val b = read(ByteArray(1), 0, 1)
            return if (b == -1) -1 else 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = inner.read(b, off, len)
            if (result > 0) {
                try {
                    val content = String(b, off, result, Charsets.US_ASCII)
                    if (content.contains(TARGET_FLAG)) {
                        val filtered = content.replace(TARGET_FLAG, REPLACEMENT)
                        val filteredBytes = filtered.toByteArray(Charsets.US_ASCII)
                        
                        // Копируем обратно в массив байтов, соблюдая границы
                        val count = Math.min(result, filteredBytes.size)
                        System.arraycopy(filteredBytes, 0, b, off, count)
                        return count
                    }
                } catch (e: Throwable) {
                    // В случае ошибки чтения просто возвращаем оригинальный результат
                }
            }
            return result
        }

        override fun available(): Int = inner.available()
        override fun close() = inner.close()
        override fun markSupported(): Boolean = inner.markSupported()
        override fun mark(readlimit: Int) = inner.mark(readlimit)
        override fun reset() = inner.reset()
        override fun skip(n: Long): Long = inner.skip(n)
    }
}