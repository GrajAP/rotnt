package dev.rotnt

/**
 * Pure DNS message logic shared by the root hotspot sinkhole (UDP socket on
 * :5353, fed via iptables REDIRECT) and the no-root self-protection VPN
 * (packets pulled out of the TUN interface).
 *
 * Deliberately free of android.* imports so it runs in plain JVM unit tests.
 * Answers 0.0.0.0 for blocked names, the gateway IP for captive-portal probe
 * hosts (so guests get the rules page), and forwards everything else to
 * upstream untouched. Never touches TLS, never rewrites content.
 */
object DnsLogic {

  const val TYPE_A = 1
  const val DEFAULT_TTL = 60L

  val UPSTREAMS = arrayOf("1.1.1.1", "9.9.9.9", "8.8.8.8")

  /**
   * Hosts whose ONLY purpose is OS connectivity probing (plain HTTP by
   * design). Resolving them to the portal IP is what makes the guest OS pop
   * the "sign in to this network" sheet. Real content hosts are never listed.
   */
  val PORTAL_HOSTS: Set<String> = setOf(
    "connectivitycheck.gstatic.com",
    "connectivitycheck.android.com",
    "captive.apple.com",
    "www.msftconnecttest.com",
    "ipv6.msftconnecttest.com",
    "www.msftncsi.com",
    "ipv6.msftncsi.com",
    "detectportal.firefox.com",
    "nmcheck.gnome.org",
    "connectivity-check.ubuntu.com"
  )

  data class Question(val name: String, val type: Int, val endOffset: Int)
  data class ParsedQuery(val id: Int, val question: Question)

  /** Null means the packet is not a usable query: drop it silently. */
  fun parse(msg: ByteArray, length: Int): ParsedQuery? {
    if (length < 12 || msg.size < length) return null
    val id = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
    val questions = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
    if (questions < 1) return null
    val q = readQuestion(msg, 12) ?: return null
    return ParsedQuery(id, q)
  }

  /**
   * Builds a complete local answer, or null when the query must go upstream.
   * Portal hosts win over the blocklist (a blocked probe would otherwise hang
   * the captive sheet instead of showing the rules).
   */
  fun responseFor(
    msg: ByteArray,
    query: ParsedQuery,
    domains: Set<String>,
    portalIp: String?,
    portalHosts: Set<String>
  ): ByteArray? {
    val name = query.question.name.lowercase()
    if (portalIp != null && isPortalHost(name, portalHosts)) {
      val ip = parseIpv4(portalIp) ?: return emptyRecord(msg, query.question, query.id)
      return if (query.question.type == TYPE_A) portalRecord(msg, query.question, query.id, ip)
      else emptyRecord(msg, query.question, query.id)
    }
    if (!isBlocked(name, domains)) return null
    return if (query.question.type == TYPE_A) nullRecord(msg, query.question, query.id, 4)
    else emptyRecord(msg, query.question, query.id)
  }

  fun isBlocked(name: String, domains: Set<String>): Boolean {
    if (name.isEmpty()) return false
    var cursor = name
    while (true) {
      if (domains.any { cursor == it || cursor.endsWith(".$it") }) return true
      val dot = cursor.indexOf('.')
      if (dot < 0) return false
      cursor = cursor.substring(dot + 1)
    }
  }

  fun isPortalHost(name: String, portalHosts: Set<String>): Boolean {
    if (name.isEmpty()) return false
    var cursor = name
    while (true) {
      if (portalHosts.any { cursor == it || cursor.endsWith(".$it") }) return true
      val dot = cursor.indexOf('.')
      if (dot < 0) return false
      cursor = cursor.substring(dot + 1)
    }
  }

  fun parseIpv4(ip: String): ByteArray? {
    val parts = ip.split(".")
    if (parts.size != 4) return null
    return try {
      ByteArray(4) { i ->
        val n = parts[i].toInt()
        if (n < 0 || n > 255) return null
        n.toByte()
      }
    } catch (t: Throwable) {
      null
    }
  }

  /**
   * Forwards the query upstream. The [exchange] lambda owns the socket (plain
   * UDP socket for the hotspot sinkhole, VPN-protected socket for self
   * protection) and either returns the raw reply or throws. Always returns a
   * valid DNS message: SERVFAIL when every upstream is unreachable, so the
   * client gets an honest error instead of hanging.
   */
  fun forwardUpstream(
    msg: ByteArray,
    length: Int,
    id: Int,
    upstreams: Array<String> = UPSTREAMS,
    exchange: (request: ByteArray, host: String) -> ByteArray
  ): ByteArray {
    val original = msg.copyOf(length)
    // Zero the ID on the wire; restore the client's ID on the way back so
    // concurrent queries sharing one socket cannot be mismatched.
    original[0] = 0
    original[1] = 0
    for (server in upstreams) {
      try {
        val data = exchange(original, server)
        if (data.size < 2) continue
        val out = data.copyOf()
        out[0] = ((id shr 8) and 0xFF).toByte()
        out[1] = (id and 0xFF).toByte()
        return out
      } catch (t: Throwable) {
        // try next upstream
      }
    }
    return byteArrayOf(
      ((id shr 8) and 0xFF).toByte(),
      (id and 0xFF).toByte(),
      0x81.toByte(), 0x82.toByte(), // QR + RA, rcode 2 = SERVFAIL
      0, 0, 0, 0, 0, 0, 0, 0
    )
  }

  fun readQuestion(msg: ByteArray, offset: Int): Question? {
    var i = offset
    val sb = StringBuilder()
    while (i < msg.size) {
      val len = msg[i].toInt() and 0xFF
      if (len == 0) {
        i++
        break
      }
      // Compression pointers never appear in the question section of a query.
      if (len and 0xC0 != 0) return null
      i++
      var j = 0
      while (j < len && i < msg.size) {
        sb.append((msg[i].toInt() and 0xFF).toChar())
        i++
        j++
      }
      if (j != len) return null
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

  private fun questionSection(msg: ByteArray, q: Question): ByteArray =
    msg.copyOfRange(12, q.endOffset)

  private fun emptyRecord(msg: ByteArray, q: Question, id: Int): ByteArray =
    header(id, 0) + questionSection(msg, q)

  private fun nullRecord(msg: ByteArray, q: Question, id: Int, rdLength: Int): ByteArray {
    val head = header(id, 1)
    val question = questionSection(msg, q)
    val name = byteArrayOf(0xC0.toByte(), 0x0C) // pointer back to the question name
    val type = byteArrayOf(0, 1) // A
    val clazz = byteArrayOf(0, 1) // IN
    val ttl = byteArrayOf(
      ((DEFAULT_TTL shr 24) and 0xFF).toByte(),
      ((DEFAULT_TTL shr 16) and 0xFF).toByte(),
      ((DEFAULT_TTL shr 8) and 0xFF).toByte(),
      (DEFAULT_TTL and 0xFF).toByte()
    )
    val rdLen = byteArrayOf(0, rdLength.toByte())
    val rdata = ByteArray(rdLength) // 0.0.0.0
    return head + question + name + type + clazz + ttl + rdLen + rdata
  }

  private fun portalRecord(msg: ByteArray, q: Question, id: Int, ip: ByteArray): ByteArray {
    val head = header(id, 1)
    val question = questionSection(msg, q)
    val name = byteArrayOf(0xC0.toByte(), 0x0C)
    val type = byteArrayOf(0, 1) // A
    val clazz = byteArrayOf(0, 1) // IN
    val ttl = byteArrayOf(
      ((DEFAULT_TTL shr 24) and 0xFF).toByte(),
      ((DEFAULT_TTL shr 16) and 0xFF).toByte(),
      ((DEFAULT_TTL shr 8) and 0xFF).toByte(),
      (DEFAULT_TTL and 0xFF).toByte()
    )
    val rdLen = byteArrayOf(0, 4)
    return head + question + name + type + clazz + ttl + rdLen + ip
  }
}
