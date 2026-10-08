package dev.rotnt

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object Hotspot {

  /**
   * T1 path. `cmd wifi start-softap` runs as shell uid, so with Shizuku we own
   * the network name without root. Note it does not enable tethering itself:
   * internet only flows if the phone already has an upstream Wi-Fi association.
   */
  fun startViaShell(ssid: String, passphrase: String): Pair<Int, String> {
    val security = if (passphrase.isBlank()) "open" else "wpa2"
    val pass = if (passphrase.isBlank()) "" else " '${passphrase.replace("'", "'\\''")}'"
    return Privs.exec("cmd wifi start-softap '${ssid.replace("'", "'\\''")}' $security$pass")
  }

  fun stopViaShell(): Pair<Int, String> = Privs.exec("cmd wifi stop-softap")

  /**
   * T0 path. Pure public API, works on any phone, but local-only: guests can
   * reach this device and nothing else.
   */
  fun startLocalOnly(
    context: Context,
    ssid: String,
    passphrase: String,
    onReady: (Map<String, Any?>) -> Unit,
    onFail: (String) -> Unit
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      onFail("local-only hotspot wymaga Androida 11+")
      return
    }
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val handler = Handler(Looper.getMainLooper())

    val callback = object : WifiManager.LocalOnlyHotspotCallback() {
      override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
        // Hold the reservation. If it gets collected the hotspot is released
        // and the network silently disappears from under the guest.
        held = reservation

        // Report the name the framework actually chose, not the one we asked
        // for. Asking is pointless: SoftApConfiguration.Builder's setters for
        // SSID and passphrase are @hide, so no public API can rename this
        // hotspot. Renaming requires shell (Shizuku) or root.
        @Suppress("DEPRECATION")
        val actual = runCatching { reservation.softApConfiguration?.ssid }.getOrNull()

        onReady(
          mapOf(
            "requestedSsid" to ssid,
            "actualSsid" to actual,
            "renamePossible" to false
          )
        )
      }

      override fun onFailed(reason: Int) {
        onFail(describe(reason))
      }
    }

    // Only this overload is reachable from a third-party app. The
    // configuration overload takes a SoftApConfiguration whose SSID and
    // passphrase cannot be set without hidden API.
    wifi.startLocalOnlyHotspot(callback, handler)
  }

  @Volatile private var held: WifiManager.LocalOnlyHotspotReservation? = null

  fun release() {
    runCatching { held?.close() }
    held = null
  }

  private fun describe(reason: Int): String = when (reason) {
    WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "brak wolnego kanału Wi-Fi"
    WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "błąd systemu przy starcie hotspotu"
    WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
      "Android odmowil startu, bo juz dziala systemowy Hotspot osobisty. " +
      "Zablokuj go w Ustawieniach (Hotspot osobisty -> wylacz) albo daj Shizuku, " +
      "zeby rotnt przejal hotspot przez shell."
    WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "system blokuje tethering dla tej aplikacji"
    else -> "nieznany błąd: $reason"
  }

  fun status(): Map<String, Any?> {
    val (code, out) = Privs.exec("cmd wifi status 2>/dev/null; echo ---; ip -o -4 addr show 2>/dev/null")
    val softapRunning = out.contains("SoftAp", ignoreCase = true) ||
      out.contains("Wi-Fi Direct", ignoreCase = true)
    val iface = if (code == 0) Blocker.hotspotInterface() else null
    return mapOf(
      "shellStatusCode" to code,
      "softapHint" to softapRunning,
      "hotspotInterface" to iface?.name,
      "hotspotAddress" to iface?.address,
      "raw" to out.take(2000)
    )
  }
}
