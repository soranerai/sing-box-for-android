package io.nekohasekai.sfa.xposed.hooks

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.nekohasekai.sfa.xposed.HookErrorStore
import java.io.FileDescriptor
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

object MtkUtils {
    private const val SOURCE = "MtkUtils"
    private val seq = AtomicInteger(1)

    private const val INTERFACES_COUNT = 20
    private val ORIGINAL_NAMES = Array(INTERFACES_COUNT) { "ccmni$it" }
    private val BAK_NAMES = Array(INTERFACES_COUNT) { "ccmni${it}_bak" }
    private const val TUN_INTERFACE = "tun0"

    private const val MAX_NAME_LEN = 15
    const val MAX_SUFFIX = 63

    // Netlink Constants
    private const val NLMSG_HEADER_LEN = 16
    private const val IFINFO_MSG_LEN = 16
    private const val NLA_HEADER_LEN = 4
    private const val RTM_NEWLINK = 16
    private const val RTM_GETLINK = 18
    private const val IFLA_IFNAME = 3
    private const val NLM_F_REQUEST = 0x1
    private const val NLM_F_ACK = 0x4
    private const val NLMSG_ERROR = 2
    
    // Interface Flags
    private const val IFF_UP = 0x1
    private const val IFF_POINTOPOINT = 0x10

    private val netlinkSocketAddressClass by lazy { Class.forName("android.system.NetlinkSocketAddress") }
    private val netlinkSocketAddressCtor by lazy {
        netlinkSocketAddressClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }

    @Volatile
    private var lastCleanupTime = 0L
    private const val CLEANUP_COOLDOWN_MS = 2000L

    fun getInterfaceIndex(name: String): Int {
        return try {
            Os.if_nametoindex(name)
        } catch (_: Throwable) {
            0
        }
    }

    fun findAvailableName(prefix: String): String? {
        val base = prefix.trim()
        if (base.isEmpty()) return null
        for (i in 0..MAX_SUFFIX) {
            val candidate = buildInterfaceName(base, i) ?: return null
            if (getInterfaceIndex(candidate) == 0) {
                return candidate
            }
        }
        return null
    }

    fun findAvailableNameForSwap(prefix: String): String? {
        val base = prefix.trim()
        if (base.isEmpty()) return null

        for (i in 0..MAX_SUFFIX) {
            val candidate = buildInterfaceName(base, i) ?: continue
            val index = getInterfaceIndex(candidate)
            
            if (index > 0) {
                if (!isInterfaceUpNetlink(index)) {
                    HookErrorStore.i(SOURCE, "Found available DOWN interface: $candidate (index $index)")
                    return candidate
                }
            }
        }
        return null
    }

    private fun buildInterfaceName(prefix: String, suffix: Int): String? {
        val suffixText = suffix.toString()
        val maxPrefixLen = MAX_NAME_LEN - suffixText.length
        if (maxPrefixLen <= 0) return null
        val trimmed = if (prefix.length > maxPrefixLen) prefix.substring(0, maxPrefixLen) else prefix
        return trimmed + suffixText
    }

    fun renameInterfaceWithSwap(oldName: String, oldIndex: Int, newName: String): String? {
        val existingIndex = getInterfaceIndex(newName)

        if (existingIndex != 0 && existingIndex != oldIndex) {
            HookErrorStore.i(SOURCE, "Target $newName occupied by index $existingIndex. Shuffling...")
                
            val tempName = newName + "_bak"
            // При бекапе чужого интерфейса нам обычно не нужно трогать P2P флаг, просто убираем с пути
            if (!renameWithNetlink(existingIndex, tempName, removeP2p = false)) {
                HookErrorStore.e(SOURCE, "Failed to shuffle existing interface")
                return null
            }
            HookErrorStore.i(SOURCE, "Moved existing $newName to $tempName")
        }

        // Переименовываем целевой интерфейс и гарантированно снимаем флаг P2P
        if (!renameWithNetlink(oldIndex, newName, removeP2p = true)) {
            HookErrorStore.e(SOURCE, "Failed to rename $oldName to $newName")
            return null
        }
        return newName
    }

    /**
     * Универсальная функция работы с интерфейсом через Netlink.
     * Обрабатывает состояние EBUSY (гасит интерфейс, применяет изменения, поднимает обратно).
     * @param removeP2p если true, принудительно снимает флаг IFF_POINTOPOINT
     */
    fun renameWithNetlink(index: Int, newName: String?, removeP2p: Boolean = false): Boolean {
        val fd = openNetlinkSocket() ?: return false
        try {
            // Маска флагов, которые мы хотим ИЗМЕНИТЬ
            val changeMask = if (removeP2p) IFF_POINTOPOINT else 0
            // Значения флагов (0, потому что мы хотим выключить биты из changeMask)
            val flags = 0 

            // ПОПЫТКА 1: Пробуем изменить "на живую"
            val renameResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, newName, flags, changeMask, seq.getAndIncrement()),
                OsConstants.EBUSY
            ) ?: return false

            if (renameResult == 0) return true
            
            if (renameResult != OsConstants.EBUSY) {
                HookErrorStore.e(SOURCE, "rename interface: netlink ack errno=$renameResult")
                return false
            }

            // --- БЛОК ОБРАБОТКИ EBUSY ---
            
            // 1. Опускаем интерфейс (DOWN)
            // Устанавливаем маску IFF_UP, а значение 0
            val downResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, null, 0, IFF_UP, seq.getAndIncrement())
            ) ?: return false

            if (downResult != 0) {
                HookErrorStore.e(SOURCE, "rename interface: set down failed errno=$downResult")
                return false
            }

            // 2. Ретрай: Переименование + снятие P2P
            // ВАЖНО: Добавляем IFF_UP в маску (чтобы он точно остался DOWN во время операции), значение = 0
            val retryChangeMask = IFF_UP or changeMask
            val retryResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, newName, 0, retryChangeMask, seq.getAndIncrement())
            ) ?: return false

            if (retryResult != 0) {
                HookErrorStore.e(SOURCE, "rename interface: retry failed errno=$retryResult")
                // Даже если упали, пытаемся поднять интерфейс обратно, чтобы не оставить его висеть DOWN
                sendNetlinkMessage(fd, buildLinkMessage(index, null, IFF_UP, IFF_UP, seq.getAndIncrement()))
                return false
            }

            // 3. Поднимаем обратно (UP)
            // Маска = IFF_UP, Значение = IFF_UP
            val upResult = sendNetlinkMessage(
                fd,
                buildLinkMessage(index, null, IFF_UP, IFF_UP, seq.getAndIncrement())
            )
            if (upResult != null && upResult != 0) {
                HookErrorStore.w(SOURCE, "rename interface: set up failed errno=$upResult")
            }
            
            return true
        } catch (e: Throwable) {
            HookErrorStore.e(SOURCE, "rename interface: netlink exception", e)
            return false
        } finally {
            try { Os.close(fd) } catch (_: Throwable) {}
        }
    }

    private fun isInterfaceUpNetlink(index: Int): Boolean {
        val fd = openNetlinkSocket() ?: return true
        try {
            val totalLength = NLMSG_HEADER_LEN + IFINFO_MSG_LEN
            val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.nativeOrder())

            buffer.putInt(totalLength)
            buffer.putShort(RTM_GETLINK.toShort())
            buffer.putShort(NLM_F_REQUEST.toShort())
            buffer.putInt(seq.getAndIncrement())
            buffer.putInt(0)

            buffer.put(0.toByte()) // AF_UNSPEC
            buffer.put(0.toByte())
            buffer.putShort(0)
            buffer.putInt(index)
            buffer.putInt(0)
            buffer.putInt(0)

            Os.write(fd, buffer.array(), 0, totalLength)

            val response = ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder())
            val read = Os.read(fd, response)
            if (read >= 32) {
                val flags = response.getInt(24)
                return (flags and IFF_UP) != 0
            }
        } catch (e: Throwable) {
            HookErrorStore.e(SOURCE, "isInterfaceUpNetlink failed", e)
        } finally {
            try { Os.close(fd) } catch (_: Throwable) {}
        }
        return true 
    }

    private fun openNetlinkSocket(): FileDescriptor? {
        return try {
            val fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, OsConstants.NETLINK_ROUTE)
            Os.setsockoptTimeval(
                fd,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(200)
            )
            val address = netlinkSocketAddressCtor.newInstance(0, 0) as SocketAddress
            Os.connect(fd, address)
            fd
        } catch (e: Throwable) {
            HookErrorStore.e(SOURCE, "Failed to open netlink socket", e)
            null
        }
    }

    private fun buildLinkMessage(index: Int, ifName: String?, flags: Int, changeMask: Int, seq: Int): ByteArray {
        val nameBytes = ifName?.let { (it + "\u0000").toByteArray(Charsets.US_ASCII) }
        val attrLen = if (nameBytes != null) NLA_HEADER_LEN + nameBytes.size else 0
        val attrAligned = align(attrLen)
        
        val totalLength = NLMSG_HEADER_LEN + IFINFO_MSG_LEN + attrAligned
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.nativeOrder())
        
        buffer.putInt(totalLength)
        buffer.putShort(RTM_NEWLINK.toShort())
        buffer.putShort((NLM_F_REQUEST or NLM_F_ACK).toShort())
        buffer.putInt(seq)
        buffer.putInt(0) // PID 0 для ядра
        
        buffer.put(0.toByte())      // ifi_family: AF_UNSPEC
        buffer.put(0.toByte())      // __ifi_pad
        buffer.putShort(0)          // ifi_type
        buffer.putInt(index)        // ifi_index
        
        // ВАЖНО: Разделяем flags (значения) и change (маска применения)
        buffer.putInt(flags) 
        buffer.putInt(changeMask)
        
        if (nameBytes != null) {
            buffer.putShort(attrLen.toShort())
            buffer.putShort(IFLA_IFNAME.toShort())
            buffer.put(nameBytes)
            val pad = attrAligned - attrLen
            repeat(pad) { buffer.put(0.toByte()) }
        }
        return buffer.array()
    }

    private fun align(length: Int): Int = (length + 3) and -4

    private fun sendNetlinkMessage(fd: FileDescriptor, message: ByteArray, suppressErrno: Int? = null): Int? {
        Os.write(fd, message, 0, message.size)
        val ack = readNetlinkAck(fd) ?: return null
        if (ack.errno != 0 && ack.errno != suppressErrno) {
            HookErrorStore.e(SOURCE, "netlink ack errno=${ack.errno} seq=${ack.seq} pid=${ack.pid}")
        }
        return ack.errno
    }

    private data class NetlinkAck(val errno: Int, val seq: Int, val pid: Int)

    private fun readNetlinkAck(fd: FileDescriptor): NetlinkAck? {
        val buffer = ByteArray(4096)
        val length = try {
            Os.read(fd, buffer, 0, buffer.size)
        } catch (e: Throwable) {
            return null
        }
        
        if (length <= 0 || length < NLMSG_HEADER_LEN) return null
        
        val byteBuffer = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.nativeOrder())
        val msgLen = byteBuffer.int
        val msgType = byteBuffer.short.toInt() and 0xFFFF
        byteBuffer.short // flags
        val msgSeq = byteBuffer.int
        val msgPid = byteBuffer.int
        
        if (msgLen < NLMSG_HEADER_LEN || msgLen > length) return null
        if (msgType != NLMSG_ERROR) return NetlinkAck(0, msgSeq, msgPid)
        if (byteBuffer.remaining() < 4) return null
        
        val error = byteBuffer.int
        val errno = if (error == 0) 0 else -error
        return NetlinkAck(errno, msgSeq, msgPid)
    }

    fun performCleanup() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCleanupTime < CLEANUP_COOLDOWN_MS) return
        lastCleanupTime = now

        try {
            if (getInterfaceIndex(TUN_INTERFACE) > 0) return 

            for (i in 0 until INTERFACES_COUNT) {
                val bakName = BAK_NAMES[i]
                val bakIndex = getInterfaceIndex(bakName)

                if (bakIndex == 0) continue

                val originalName = ORIGINAL_NAMES[i]
                val originalIndex = getInterfaceIndex(originalName)

                if (originalIndex == 0) {
                    HookErrorStore.i(SOURCE, "Cleanup: restoring $bakName -> $originalName (index $bakIndex)")
                    renameWithNetlink(bakIndex, originalName, removeP2p = false)
                }
            }
        } catch (e: Throwable) {
            HookErrorStore.e(SOURCE, "Mass cleanup failed", e)
        }
    }
}