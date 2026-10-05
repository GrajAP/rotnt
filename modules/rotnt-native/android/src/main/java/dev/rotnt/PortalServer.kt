package dev.rotnt

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captive-portal style HTTP server on 8080.
 *
 * Android guests probe the gateway for /generate_204 expecting 204. Answering
 * with a redirect is what makes the guest's OS pop the "sign in to this network"
 * sheet, which is the only way to show a message on a device we do not control.
 */
class PortalServer {

  private val running = AtomicBoolean(false)
  private var server: ServerSocket? = null
  private var acceptThread: Thread? = null
  private val pool = Executors.newFixedThreadPool(8)

  @Volatile private var config: JSONObject = JSONObject()

  fun start(port: Int, configJson: String): Map<String, Any?> {
    runCatching { config = JSONObject(configJson) }
    if (running.getAndSet(true)) return mapOf("ok" to true, "alreadyRunning" to true)

    return try {
      val socket = ServerSocket(port, 32)
      socket.reuseAddress = true
      server = socket
      acceptThread = thread(name = "rotnt-portal", isDaemon = true) {
        while (running.get() && !socket.isClosed) {
          try {
            val client = socket.accept()
            pool.execute { handle(client) }
          } catch (t: Throwable) {
            if (!running.get()) break
          }
        }
      }
      mapOf("ok" to true, "port" to port)
    } catch (t: Throwable) {
      running.set(false)
      mapOf("ok" to false, "error" to (t.message ?: "bind failed"))
    }
  }

  fun stop(): Map<String, Any?> {
    running.set(false)
    runCatching { server?.close() }
    server = null
    acceptThread = null
    pool.shutdownNow()
    return mapOf("ok" to true)
  }

  fun isRunning(): Boolean = running.get()

  private fun handle(client: Socket) {
    client.use { socket ->
      socket.soTimeout = 5000
      val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      val output = socket.getOutputStream()
      val requestLine = input.readLine() ?: return
      while (true) {
        val header = input.readLine() ?: break
        if (header.isEmpty()) break
      }

      val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"

      when {
        path == "/health" -> respondText(output, 200, "ok")
        path == "/generate_204" ||
          path == "/gen_204" ||
          path == "/hotspot-detect.html" ||
          path == "/canonical.html" ||
          path == "/success.txt" -> redirect(output, "/")
        else -> respondHtml(output, 200, render())
      }
    }
  }

  private fun redirect(output: OutputStream, location: String) {
    val body = "<html><body>redirecting</body></html>"
    val head = buildString {
      append("HTTP/1.1 302 Found\r\n")
      append("Location: $location\r\n")
      append("Content-Type: text/html; charset=utf-8\r\n")
      append("Content-Length: ${body.toByteArray().size}\r\n")
      append("Connection: close\r\n\r\n")
    }
    output.write(head.toByteArray())
    output.write(body.toByteArray())
    output.flush()
  }

  private fun respondText(output: OutputStream, code: Int, text: String) {
    val body = text.toByteArray()
    val head = buildString {
      append("HTTP/1.1 $code OK\r\n")
      append("Content-Type: text/plain; charset=utf-8\r\n")
      append("Content-Length: ${body.size}\r\n")
      append("Connection: close\r\n\r\n")
    }
    output.write(head.toByteArray())
    output.write(body)
    output.flush()
  }

  private fun respondHtml(output: OutputStream, code: Int, html: String) {
    val body = html.toByteArray()
    val head = buildString {
      append("HTTP/1.1 $code OK\r\n")
      append("Content-Type: text/html; charset=utf-8\r\n")
      append("Content-Length: ${body.size}\r\n")
      append("Cache-Control: no-store\r\n")
      append("Connection: close\r\n\r\n")
    }
    output.write(head.toByteArray())
    output.write(body)
    output.flush()
  }

  private fun render(): String {
    val name = esc(optString("appName", "rotnt"))
    val ssid = esc(optString("ssid", ""))
    val owner = esc(optString("owner", "właściciel tej sieci"))
    val tier = esc(optString("tierLabel", "tryb informacyjny"))
    val installUrl = esc(optString("installUrl", ""))
    val note = esc(optString("note", ""))
    val rules = config.optJSONArray("rules")

    val ruleRows = StringBuilder()
    if (rules != null) {
      for (i in 0 until rules.length()) {
        val r = rules.optJSONObject(i) ?: continue
        val blocked = r.optBoolean("blocked", true)
        ruleRows.append(
          """<li class="${if (blocked) "off" else "on"}">
                <span class="mark">${if (blocked) "×" else "✓"}</span>
                <span class="app">${esc(r.optString("name"))}</span>
                <span class="why">${esc(r.optString("reason"))}</span>
              </li>"""
        )
      }
    }

    val cta = if (installUrl.isNotBlank()) {
      """<a class="cta" href="$installUrl">Zainstaluj rotnt na swoim telefonie</a>
         <p class="tiny">Wtedy ta sieć chroni też Ciebie.</p>"""
    } else {
      """<p class="tiny">Ta sieć działa bez aplikacji. Pobierz rotnt, żeby sam mieć takie reguły.</p>"""
    }

    val ssidLine = if (ssid.isNotBlank()) """<p class="ssid">Sieć: <b>$ssid</b></p>""" else ""

    return """
<!doctype html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$name</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 24px 20px 48px;
    font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: #0d1117; color: #e6edf3;
    display: flex; justify-content: center;
  }
  main { width: 100%; max-width: 460px; }
  h1 { font-size: 30px; margin: 0 0 4px; letter-spacing: -0.02em; }
  .tag { color: #7d8590; font-size: 13px; text-transform: uppercase; letter-spacing: .08em; }
  .lede { margin: 20px 0 8px; font-size: 15px; color: #adbac7; }
  $ssidLine
  h2 { font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: #7d8590; margin: 28px 0 10px; }
  ul { list-style: none; padding: 0; margin: 0; }
  li {
    display: flex; align-items: center; gap: 12px;
    padding: 14px 16px; border-radius: 10px; margin-bottom: 8px;
    background: #161b22; border: 1px solid #30363d;
  }
  .mark { width: 24px; height: 24px; border-radius: 6px; display: grid; place-items: center;
          font-weight: 700; font-size: 15px; flex: none; }
  li.off .mark { background: #3d1d1d; color: #ff7b72; }
  li.on .mark { background: #16301f; color: #3fb950; }
  .app { font-weight: 600; }
  .why { margin-left: auto; font-size: 12px; color: #7d8590; text-align: right; max-width: 55%; }
  .box { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 18px; margin-top: 28px; }
  .box p { margin: 0 0 14px; font-size: 14px; color: #adbac7; }
  .cta {
    display: block; text-align: center; text-decoration: none; font-weight: 600;
    background: #238636; color: #fff; padding: 14px; border-radius: 8px;
  }
  .tiny { font-size: 12px; color: #7d8590; margin: 10px 0 0; }
  footer { margin-top: 28px; font-size: 12px; color: #6e7681; }
  code { background: #161b22; padding: 2px 6px; border-radius: 4px; font-size: 12px; }
</style>
</head>
<body>
<main>
  <div class="tag">$tier</div>
  <h1>$name</h1>
  <p class="lede">Ta sieć ma zasady. Ustawia je $owner, nie operator.</p>
  $ssidLine

  <h2>Reguły tej sieci</h2>
  <ul>$ruleRows</ul>

  <div class="box">
    <p>$note</p>
    $cta
  </div>

  <footer>
    Zamiast dokładnie „zablokowane" często wystarczy świadoma decyzja.
    Reguły ustawiasz sam, na swoim telefonie.
  </footer>
</main>
</body>
</html>
""".trimIndent()
  }

  private fun optString(key: String, fallback: String): String =
    if (config.has(key)) config.optString(key, fallback) else fallback

  private fun esc(input: String): String = input
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
}
