package dev.rotnt

import android.os.Process

data class Iface(val name: String, val address: String, val isUp: Boolean)

object Blocker {

  private const val NAT_CHAIN = "ROTNT"
  private const val FILTER_CHAIN = "ROTNT"
  private const val DNS_PORT = 5353

  /**
   * Finds the interface the tethering hotspot is actually running on.
   *
   * There is no API for this. Android names it wlan1, ap0, swlan0, ap+wlan0 or
   * br-lan depending on version and OEM, so we read it out of `ip addr` and
   * score the candidates.
   */
  fun detectInterfaces(): List<Iface> {
    val (code, out) = Privs.exec("ip -o -4 addr show 2>/dev/null; echo ---; ip -o link show 2>/dev/null")
    if (code != 0) return emptyList()

    val down = out.substringAfter("---", "").lineSequence()
      .mapNotNull { line ->
        val m = Regex("^\\d+:\\s+([^:@]+)[:@]").find(line) ?: return@mapNotNull null
        m.groupValues[1] to (line.contains("state UP") || line.contains(",UP"))
      }.toMap()

    return out.substringBefore("---").lineSequence().mapNotNull { line ->
      val m = Regex("^\\d+:\\s+([^:@]+)[:@].*?inet\\s+([0-9.]+)/").find(line)
        ?: return@mapNotNull null
      val name = m.groupValues[1]
      val address = m.groupValues[2]
      Iface(name, address, down[name] ?: false)
    }.filter { it.name != "lo" }.toList()
  }

  fun hotspotInterface(): Iface? = detectInterfaces()
    .filter { isHotspotCandidate(it) }
    .maxByOrNull { score(it) }

  fun isHotspotCandidate(iface: Iface): Boolean = iface.address.startsWith("192.168.43.") ||
    iface.address.startsWith("192.168.42.") ||
    iface.name == "ap0" ||
    iface.name == "swlan0" ||
    iface.name.startsWith("ap+") ||
    iface.name == "br-lan"

  private fun score(iface: Iface): Int {
    var s = 0
    if (iface.name.startsWith("wlan") && iface.name != "wlan0") s += 100
    if (iface.name == "ap0" || iface.name == "swlan0") s += 90
    if (iface.name.startsWith("ap+")) s += 80
    if (iface.name == "br-lan") s += 70
    if (iface.address.startsWith("192.168.43.")) s += 50
    if (iface.isUp) s += 10
    return s
  }

  /**
   * Points guest DNS at our sinkhole and closes the DoT escape hatch.
   * Everything lives in a private chain so teardown is one delete per interface
   * and we can never leave the hotspot's own NAT half-modified.
   */
  fun arm(iface: String, blockSelf: Boolean): String {
    if (!Privs.isRootAvailable()) return "root required"

    val log = StringBuilder()
    val uid = Process.myUid()

    run("iptables -t nat -N $NAT_CHAIN 2>/dev/null", log, allowFailure = true)
    run("iptables -t nat -F $NAT_CHAIN", log, allowFailure = true)
    run("iptables -N $FILTER_CHAIN 2>/dev/null", log, allowFailure = true)
    run("iptables -F $FILTER_CHAIN", log, allowFailure = true)

    // Guest DNS -> our sinkhole. PREROUTING so this happens before the
    // hotspot's own dnsmasq ever gets to answer.
    run("iptables -t nat -A $NAT_CHAIN -p udp --dport 53 -j REDIRECT --to-port $DNS_PORT", log)
    run("iptables -t nat -A PREROUTING -i $iface -j $NAT_CHAIN", log)

    // Our own answers are never truncated, so nothing legitimately needs TCP DNS;
    // dropping it stops the obvious bypass. 853 is DNS-over-TLS.
    run("iptables -A $FILTER_CHAIN -p tcp --dport 53 -j DROP", log)
    run("iptables -A $FILTER_CHAIN -p tcp --dport 853 -j DROP", log)
    run("iptables -A FORWARD -i $iface -j $FILTER_CHAIN", log)

    if (blockSelf) {
      // Exclude our own uid, otherwise the sinkhole's forwarder would resolve
      // against itself and loop.
      run(
        "iptables -t nat -A OUTPUT -p udp --dport 53 -m owner ! --uid-owner $uid -j REDIRECT --to-port $DNS_PORT",
        log
      )
      run("iptables -A OUTPUT -p tcp --dport 853 -j DROP", log)
    }

    run("net.ipv4.ip_forward=1", log, allowFailure = true)
    return log.toString()
  }

  fun disarm(iface: String?, blockSelf: Boolean): String {
    if (!Privs.isRootAvailable()) return "root required"
    val log = StringBuilder()

    iface?.let {
      run("iptables -t nat -D PREROUTING -i $it -j $NAT_CHAIN", log, allowFailure = true)
      run("iptables -D FORWARD -i $it -j $FILTER_CHAIN", log, allowFailure = true)
    }
    run("iptables -t nat -F $NAT_CHAIN", log, allowFailure = true)
    run("iptables -t nat -X $NAT_CHAIN", log, allowFailure = true)
    run("iptables -F $FILTER_CHAIN", log, allowFailure = true)
    run("iptables -X $FILTER_CHAIN", log, allowFailure = true)

    if (blockSelf) {
      run(
        "iptables -t nat -D OUTPUT -p udp --dport 53 -m owner ! --uid-owner ${Process.myUid()} -j REDIRECT --to-port $DNS_PORT",
        log, allowFailure = true
      )
      run("iptables -D OUTPUT -p tcp --dport 853 -j DROP", log, allowFailure = true)
    }
    return log.toString()
  }

  private fun run(cmd: String, log: StringBuilder, allowFailure: Boolean = false) {
    val (code, out) = Privs.exec(cmd)
    if (code != 0) {
      log.appendLine("[$code] $cmd -> ${out.trim()}")
      if (!allowFailure) log.appendLine("  ^ FAILED")
    } else {
      log.appendLine("ok: $cmd")
    }
  }
}
