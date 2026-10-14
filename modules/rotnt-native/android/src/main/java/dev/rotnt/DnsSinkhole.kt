package dev.rotnt

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Minimal DNS sinkhole. Answers 0.0.0.0 for blocked names, the portal
 * (gateway) IP for captive-portal probe hosts, forwards everything else to
 * upstream untouched (we only rewrite the transaction ID on the way back).
 *
 * Runs on UDP 5353 so it does not need root itself; iptables REDIRECT is what
 * points guest traffic at it.
 */
class DnsSinkhole(private val ports: () -> Set<String>) {

  private val running = AtomicBoolean(false)
  private val blockedHits = ConcurrentHashMap<String, Int>()
  private var socket: DatagramSocket? = null
  private var thread: Thread? = null

  /** Gateway IP handed to captive-portal probes, set when the barrier arms. */
  @Volatile var portalIp: String? = null
  @Volatile var portalHosts: Set<String> = DnsLogic.PORTAL_HOSTS

  fun setPortal(ip: String?, hosts: Set<String> = DnsLogic.PORTAL_HOSTS) {
    portalIp = ip
    portalHosts = hosts
  }

  fun start() {
    if (!running.compareAndSet(false, true)) return
    val s = try {
      DatagramSocket(5353)
    } catch (t: Throwable) {
      // A failed bind used to leave running=true with no thread behind it,
      // which wedged every later start() into a permanent no-op.
      running.set(false)
      return
    }
    s.reuseAddress = true
    socket = s
    thread = thread(name = "rotnt-dns", isDaemon = true) {
      val buf = ByteArray(4096)
      while (running.get() && !s.isClosed) {
        try {
          val packet = DatagramPacket(buf, buf.size)
          s.receive(packet)
          val response = handle(buf.copyOf(packet.length), packet.length)
          if (response != null) {
            val reply = DatagramPacket(response, response.size, packet.address, packet.port)
            s.send(reply)
          }
        } catch (t: Throwable) {
          if (!running.get()) break
        }
      }
    }
  }

  fun stop() {
    running.set(false)
    runCatching { socket?.close() }
    socket = null
    thread = null
  }

  fun isRunning(): Boolean = running.get()

  fun hits(): Map<String, Int> = blockedHits.toMap()

  private fun handle(msg: ByteArray, length: Int): ByteArray? {
    val query = DnsLogic.parse(msg, length) ?: return null
    val local = DnsLogic.responseFor(msg, query, ports(), portalIp, portalHosts)
    if (local != null) {
      val name = query.question.name.lowercase()
      if (DnsLogic.isBlocked(name, ports())) blockedHits.merge(name, 1, Int::plus)
      return local
    }
    return DnsLogic.forwardUpstream(msg, length, query.id) { request, host ->
      DatagramSocket().use { out ->
        out.soTimeout = 3000
        val p = DatagramPacket(request, request.size, InetAddress.getByName(host), 53)
        out.send(p)
        val buf = ByteArray(4096)
        val reply = DatagramPacket(buf, buf.size)
        out.receive(reply)
        reply.data.copyOf(reply.length)
      }
    }
  }
}
