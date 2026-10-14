package dev.rotnt

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM tests for the DNS filter both enforcement paths share. No Android
 * framework, no sockets, no device needed.
 */
class DnsLogicTest {

  private val domains = setOf("tiktok.com", "tiktokcdn.com", "musical.ly")

  /** Minimal DNS query builder: header + one question. */
  private fun query(name: String, type: Int = 1, id: Int = 0x1234): ByteArray {
    val labels = name.split(".")
    val qname = mutableListOf<Byte>()
    for (label in labels) {
      qname.add(label.length.toByte())
      for (c in label) qname.add(c.code.toByte())
    }
    qname.add(0)
    val head = byteArrayOf(
      ((id shr 8) and 0xFF).toByte(), (id and 0xFF).toByte(),
      0x01, 0x00, // RD
      0, 1, 0, 0, 0, 0, 0, 0
    )
    val qt = byteArrayOf(((type shr 8) and 0xFF).toByte(), (type and 0xFF).toByte(), 0, 1)
    return head + qname.toByteArray() + qt
  }

  private fun rcode(resp: ByteArray): Int = resp[3].toInt() and 0xF
  private fun ancount(resp: ByteArray): Int = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)

  @Test fun `blockedA GetsNullRouted`() {
    val q = query("v16m.tiktokcdn.com")
    val parsed = DnsLogic.parse(q, q.size)!!
    assertEquals("v16m.tiktokcdn.com", parsed.question.name)
    val resp = DnsLogic.responseFor(q, parsed, domains, null, emptySet())!!
    assertEquals(0, rcode(resp))
    assertEquals(1, ancount(resp))
    // rdata is the last 4 bytes and must be 0.0.0.0
    assertArrayEquals(byteArrayOf(0, 0, 0, 0), resp.copyOfRange(resp.size - 4, resp.size))
    // transaction id preserved
    assertEquals(0x12, resp[0].toInt() and 0xFF)
    assertEquals(0x34, resp[1].toInt() and 0xFF)
  }

  @Test fun `subdomainOfBlocked IsBlocked`() {
    assertTrue(DnsLogic.isBlocked("x.tiktok.com", domains))
    assertTrue(DnsLogic.isBlocked("a.b.tiktokcdn.com", domains))
    assertFalse(DnsLogic.isBlocked("nottiktok.com", domains))
    assertFalse(DnsLogic.isBlocked("tiktok.com.evil.com", domains))
    assertFalse(DnsLogic.isBlocked("google.com", domains))
  }

  @Test fun `blockedAaaa GetsEmptyNoerror`() {
    val q = query("tiktok.com", type = 28)
    val parsed = DnsLogic.parse(q, q.size)!!
    val resp = DnsLogic.responseFor(q, parsed, domains, null, emptySet())!!
    assertEquals(0, rcode(resp))
    assertEquals(0, ancount(resp))
  }

  @Test fun `cleanDomain GoesUpstream`() {
    val q = query("example.com")
    val parsed = DnsLogic.parse(q, q.size)!!
    assertNull(DnsLogic.responseFor(q, parsed, domains, null, emptySet()))
  }

  @Test fun `probeHost ResolvesToPortal`() {
    val q = query("connectivitycheck.gstatic.com")
    val parsed = DnsLogic.parse(q, q.size)!!
    val resp = DnsLogic.responseFor(q, parsed, domains, "192.168.43.1", DnsLogic.PORTAL_HOSTS)!!
    assertEquals(0, rcode(resp))
    assertEquals(1, ancount(resp))
    assertArrayEquals(
      byteArrayOf(192.toByte(), 168.toByte(), 43, 1),
      resp.copyOfRange(resp.size - 4, resp.size)
    )
  }

  @Test fun `realSite NeverHijackedAsPortal`() {
    val q = query("www.apple.com")
    val parsed = DnsLogic.parse(q, q.size)!!
    // www.apple.com is NOT a pure probe host: must go upstream, never portal
    assertNull(DnsLogic.responseFor(q, parsed, domains, "192.168.43.1", DnsLogic.PORTAL_HOSTS))
  }

  @Test fun `garbage IsDropped`() {
    assertNull(DnsLogic.parse(byteArrayOf(1, 2, 3), 3))
    assertNull(DnsLogic.parse(ByteArray(12), 12)) // qdcount = 0
  }

  @Test fun `forward RewritesIdAndServfailsHonestly`() {
    val q = query("example.com", id = 0xABCD)
    val fakeReply = byteArrayOf(0, 0, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
    var seenHost = ""
    val out = DnsLogic.forwardUpstream(q, q.size, 0xABCD) { req, host ->
      seenHost = host
      // upstream must see a zeroed id
      assertEquals(0, req[0].toInt())
      assertEquals(0, req[1].toInt())
      fakeReply
    }
    assertEquals("1.1.1.1", seenHost)
    assertEquals(0xAB, out[0].toInt() and 0xFF)
    assertEquals(0xCD, out[1].toInt() and 0xFF)

    val fail = DnsLogic.forwardUpstream(q, q.size, 0x0001) { _, _ -> throw RuntimeException("down") }
    assertEquals(2, fail[3].toInt() and 0xF) // SERVFAIL, not a hang
  }
}
