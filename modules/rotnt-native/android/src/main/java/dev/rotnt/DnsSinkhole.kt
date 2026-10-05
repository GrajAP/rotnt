package dev.rotnt

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Minimal DNS sinkhole. Answers 0.0.0.0 for blocked names, forwards everything
 * else to upstream untouched (we only rewrite the transaction ID on the way back).
 *
 * Runs on UDP 5353 so it does not need root itself; iptables REDIRECT is what
 * points guest traffic at it.
 */
class DnsSinkhole(private val ports: () -> Set<String>) {

  private val upstream = arrayOf("1.1.1.1", "9.9.9.9", "8.8.8.8")
  private val ttlSeconds = 60L
  private val running = AtomicBoolean(false)
  private val blockedHits = ConcurrentHashMap<String, Int>()
  private var socket: DatagramSocket? = null
  private var thread: Thread? = null

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
    if (length < 12) return null
    val id = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
    val questions = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
    if (questions < 1) return null

    val q = readQuestion(msg, 12) ?: return null
    val name = q.name.lowercase()
    val blocked = isBlocked(name, ports())

    if (!blocked) return forward(msg, length, id)

    blockedHits.merge(name, 1, Int::plus)
    return if (q.type == TYPE_A) {
      nullRecord(msg, q, id, 4)
    } else {
      // AAAA and everything else: NOERROR with no answers, so clients fail fast
      // instead of hanging on a v6 path we cannot sinkhole.
      emptyRecord(msg, q, id)
    }
  }

  private fun isBlocked(name: String, ports: Set<String>): Boolean {
    if (name.isEmpty()) return false
    var cursor = name
    while (true) {
      if (ports.any { cursor == it || cursor.endsWith(".$it") }) return true
      val dot = cursor.indexOf('.')
      if (dot < 0) return false
      cursor = cursor.substring(dot + 1)
    }
  }

  private fun forward(msg: ByteArray, length: Int, id: Int): ByteArray? {
    val original = msg.copyOf(length)
    var lastError: Throwable? = null
    for (server in upstream) {
      try {
        DatagramSocket().use { out ->
          out.soTimeout = 3000
          val request = original.copyOf()
          request[0] = 0
          request[1] = 0
          val p = DatagramPacket(request, request.size, InetAddress.getByName(server), 53)
          out.send(p)
          val buf = ByteArray(4096)
          val reply = DatagramPacket(buf, buf.size)
          out.receive(reply)
          val data = reply.data.copyOf(reply.length)
          data[0] = ((id shr 8) and 0xFF).toByte()
          data[1] = (id and 0xFF).toByte()
          return data
        }
      } catch (t: Throwable) {
        lastError = t
      }
    }
    // Upstream unreachable. Returning SERVFAIL keeps the guest honest about being offline
    // instead of pretending the app was blocked.
    return byteArrayOf(
      ((id shr 8) and 0xFF).toByte(),
      (id and 0xFF).toByte(),
      0x81.toByte(), 0x82.toByte(), // QR + RA, rcode 2 = SERVFAIL
      0, 0, 0, 0, 0, 0, 0, 0
    ).also { lastError?.printStackTrace() }
  }

  private class Question(val name: String, val type: Int, val endOffset: Int)

  private fun readQuestion(msg: ByteArray, offset: Int): Question? {
    var i = offset
    val sb = StringBuilder()
    while (i < msg.size) {
      val len = msg[i].toInt() and 0xFF
      if (len == 0) {
        i++
        break
      }
      i++
      var j = 0
      while (j < len && i < msg.size) {
        sb.append((msg[i].toInt() and 0xFF).toChar())
        i++
        j++
      }
      sb.append('.')
    }
    if (i + 4 > msg.size) return null
    val type = ((msg[i].toInt() and 0xFF) shl 8) or (msg[i + 1].toInt() and 0xFF)
    return Question(sb.toString().trimEnd('.'), type, i + 4)
  }

  private fun header(id: Int, ancount: Int): ByteArray {
    val hi = ((id shr 8) and 0xFF).toByte()
    val lo = (id and 0xFF).toByte()
    return byteArrayOf(
      hi, lo,
      0x81.toByte(), 0x80.toByte(), // QR, RD, RA, rcode 0
      0, 1, // QDCOUNT = 1
      0, ancount.toByte(), // ANCOUNT
      0, 0, // NSCOUNT
      0, 0 // ARCOUNT
    )
  }

  private fun emptyRecord(msg: ByteArray, q: Question, id: Int): ByteArray {
    val head = header(id, 0)
    val question = msg.copyOfRange(12, q.endOffset)
    return head + question
  }

  private fun nullRecord(msg: ByteArray, q: Question, id: Int, rdLength: Int): ByteArray {
    val head = header(id, 1)
    val question = msg.copyOfRange(12, q.endOffset)
    val name = byteArrayOf(0xC0.toByte(), 0x0C) // pointer back to the question name
    val type = byteArrayOf(0, 1) // A
    val clazz = byteArrayOf(0, 1) // IN
    val ttl = byteArrayOf(
      ((ttlSeconds shr 24) and 0xFF).toByte(),
      ((ttlSeconds shr 16) and 0xFF).toByte(),
      ((ttlSeconds shr 8) and 0xFF).toByte(),
      (ttlSeconds and 0xFF).toByte()
    )
    val rdLen = byteArrayOf(0, rdLength.toByte())
    val rdata = ByteArray(rdLength)
    return head + question + name + type + clazz + ttl + rdLen + rdata
  }

  companion object {
    private const val TYPE_A = 1
  }
}
