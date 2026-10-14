package dev.rotnt

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Self-protection without root: a DNS-only local VPN.
 *
 * The TUN interface carries a single /32 route for a fake DNS server
 * (10.0.0.2) which we also advertise via addDnsServer. Only DNS queries enter
 * the tunnel; every other packet keeps flowing over the physical interface, so
 * there is no userspace TCP stack to maintain and no throughput penalty.
 * Blocked names get 0.0.0.0, everything else is forwarded to upstream.
 *
 * This is the exact same consent pattern as Blokada / Rethink / NetGuard:
 * the system shows the standard VPN dialog (via VpnService.prepare), the OS
 * keeps a persistent "VPN is active" notice, and the user can revoke at any
 * time from system settings (onRevoke). No packet content is inspected beyond
 * the DNS question, no TLS is intercepted. DoH/DoT inside apps bypasses any
 * DNS filter by design; the app warns about Private DNS instead of
 * pretending otherwise.
 */
class SelfVpnService : VpnService() {

  companion object {
    const val ACTION_START = "dev.rotnt.action.VPN_START"
    const val ACTION_STOP = "dev.rotnt.action.VPN_STOP"

    const val VPN_ADDRESS = "10.0.0.2"
    const val VPN_MTU = 1500

    /** Blocklist active for the current session, set by RotntModule before start. */
    @Volatile var domains: Set<String> = emptySet()

    @Volatile var running: Boolean = false
      private set

    private val hits = ConcurrentHashMap<String, Int>()
    fun snapshotHits(): Map<String, Int> = hits.toMap()
    fun resetHits() = hits.clear()
  }

  private var tun: ParcelFileDescriptor? = null
  private val stopFlag = AtomicBoolean(false)
  private var worker: Thread? = null
  private var upstream: DatagramSocket? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        teardown()
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        boot()
        return START_STICKY
      }
    }
  }

  private fun boot() {
    if (running) return
    val fd = try {
      Builder()
        .setSession("rotnt")
        .setMtu(VPN_MTU)
        .addAddress(VPN_ADDRESS, 32)
        .addDnsServer(VPN_ADDRESS)
        .addRoute(VPN_ADDRESS, 32)
        // No allowBypass() call: bypass stays disallowed by default, so apps
        // cannot sidestep the filter by binding the underlying network
        // directly. Normal routing is unaffected: only the /32 DNS route
        // enters the tunnel.
        .establish()
    } catch (t: Throwable) {
      null
    }
    if (fd == null) {
      stopSelf()
      return
    }
    val sock = try {
      DatagramSocket().also {
        // Belt and braces: with a /32 route our upstream replies never enter
        // the tunnel anyway, but protect() rules out any forwarding loop.
        runCatching { protect(it) }
        it.soTimeout = 3000
      }
    } catch (t: Throwable) {
      runCatching { fd.close() }
      stopSelf()
      return
    }
    tun = fd
    upstream = sock
    stopFlag.set(false)
    running = true
    worker = thread(name = "rotnt-selfvpn", isDaemon = true) {
      val input = FileInputStream(fd.fileDescriptor)
      val output = FileOutputStream(fd.fileDescriptor)
      val buf = ByteArray(32767)
      while (!stopFlag.get()) {
        val len = try {
          input.read(buf)
        } catch (t: Throwable) {
          if (stopFlag.get()) break else continue
        }
        if (len <= 0) continue
        val resp = try {
          handleIpPacket(buf, len)
        } catch (t: Throwable) {
          null
        } ?: continue
        try {
          output.write(resp)
        } catch (t: Throwable) {
          if (stopFlag.get()) break
        }
      }
    }
  }

  private fun handleIpPacket(buf: ByteArray, len: Int): ByteArray? {
    if (len < 28) return null
    val version = (buf[0].toInt() shr 4) and 0xF
    if (version != 4) return null // no v6 route is advertised; ignore
    val ihl = (buf[0].toInt() and 0xF) * 4
    if (ihl < 20 || len < ihl + 8) return null
    if ((buf[9].toInt() and 0xFF) != 17) return null // UDP only
    val udpOff = ihl
    val srcPort = u16(buf, udpOff)
    val dstPort = u16(buf, udpOff + 2)
    if (dstPort != 53) return null
    val udpLen = u16(buf, udpOff + 4)
    if (udpLen < 8 || udpOff + udpLen > len) return null
    val dnsOff = udpOff + 8
    val dnsLen = udpLen - 8
    if (dnsLen <= 0) return null
    val dns = buf.copyOfRange(dnsOff, dnsOff + dnsLen)

    val query = DnsLogic.parse(dns, dnsLen) ?: return null
    val current = domains
    val local = DnsLogic.responseFor(dns, query, current, null, emptySet())
    val answer = if (local != null) {
      if (DnsLogic.isBlocked(query.question.name.lowercase(), current)) {
        hits.merge(query.question.name.lowercase(), 1, Int::plus)
      }
      local
    } else {
      val sock = upstream ?: return null
      DnsLogic.forwardUpstream(dns, dnsLen, query.id) { request, host ->
        val p = DatagramPacket(request, request.size, InetAddress.getByName(host), 53)
        sock.send(p)
        val rbuf = ByteArray(4096)
        val reply = DatagramPacket(rbuf, rbuf.size)
        sock.receive(reply)
        reply.data.copyOf(reply.length)
      }
    }
    return buildResponsePacket(buf, srcPort, answer)
  }

  /**
   * Wraps a DNS answer in IPv4/UDP, swapping source and destination. UDP
   * checksum is computed properly (not zeroed) so picky OEM stacks accept it.
   */
  private fun buildResponsePacket(orig: ByteArray, origSrcPort: Int, dns: ByteArray): ByteArray {
    val total = 20 + 8 + dns.size
    val out = ByteArray(total)
    out[0] = 0x45
    out[1] = 0
    out[2] = ((total shr 8) and 0xFF).toByte()
    out[3] = (total and 0xFF).toByte()
    // identification, flags/fragment: 0
    out[8] = 64 // TTL
    out[9] = 17 // UDP
    // checksum at 10..11 filled below
    orig.copyInto(out, 12, 16, 20) // src = our VPN address (original dst)
    orig.copyInto(out, 16, 12, 16) // dst = original src
    val ipSum = checksum(out, 0, 20)
    out[10] = ((ipSum shr 8) and 0xFF).toByte()
    out[11] = (ipSum and 0xFF).toByte()
    // UDP header
    out[20] = 0
    out[21] = 53
    out[22] = ((origSrcPort shr 8) and 0xFF).toByte()
    out[23] = (origSrcPort and 0xFF).toByte()
    val udpLen = 8 + dns.size
    out[24] = ((udpLen shr 8) and 0xFF).toByte()
    out[25] = (udpLen and 0xFF).toByte()
    // checksum at 26..27 filled below
    dns.copyInto(out, 28)
    val udpSum = udpChecksum(out)
    val wire = if (udpSum == 0) 0xFFFF else udpSum
    out[26] = ((wire shr 8) and 0xFF).toByte()
    out[27] = (wire and 0xFF).toByte()
    return out
  }

  private fun u16(b: ByteArray, i: Int): Int =
    ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

  private fun checksum(b: ByteArray, off: Int, len: Int): Int {
    var sum = 0
    var i = off
    while (i < off + len - 1) {
      sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
      if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
      i += 2
    }
    if (i < off + len) {
      sum += (b[i].toInt() and 0xFF) shl 8
      if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
    }
    return sum.inv() and 0xFFFF
  }

  private fun udpChecksum(packet: ByteArray): Int {
    var sum = 0
    // pseudo-header: src(12..16), dst(16..20), zero+proto, udp length(24..26)
    val parts = mutableListOf<Int>()
    for (i in 12 until 20 step 2) parts.add(u16(packet, i))
    parts.add(17) // protocol UDP
    parts.add(u16(packet, 24)) // udp length
    // udp header (checksum field treated as zero) + payload
    var i = 20
    while (i < packet.size - 1) {
      if (i == 26) {
        parts.add(0)
      } else {
        parts.add(u16(packet, i))
      }
      i += 2
    }
    if (i < packet.size) parts.add((packet[i].toInt() and 0xFF) shl 8)
    for (p in parts) {
      sum += p
      if (sum > 0xFFFF) sum = (sum and 0xFFFF) + 1
    }
    return sum.inv() and 0xFFFF
  }

  override fun onRevoke() {
    teardown()
    stopSelf()
    super.onRevoke()
  }

  override fun onDestroy() {
    teardown()
    super.onDestroy()
  }

  private fun teardown() {
    stopFlag.set(true)
    running = false
    runCatching { tun?.close() }
    tun = null
    runCatching { upstream?.close() }
    upstream = null
    worker = null
  }
}
