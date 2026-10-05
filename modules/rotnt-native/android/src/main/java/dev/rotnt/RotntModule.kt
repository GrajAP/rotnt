package dev.rotnt

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import rikka.shizuku.Shizuku

class RotntModule : Module() {

  private val sinkhole = DnsSinkhole { currentPorts }
  private var portal = PortalServer()
  private var currentPorts: Set<String> = emptySet()
  private var armedIface: String? = null
  private var blockSelf = false

  override fun definition() = ModuleDefinition {
    Name("Rotnt")

    AsyncFunction("capabilities") {
      val tier = Privs.tier()
      mapOf(
        "tier" to tier.name,
        "canNameHotspot" to tier.canNameHotspot,
        "canBlockGuests" to tier.canBlockGuests,
        "rootAvailable" to Privs.isRootAvailable(),
        "shizukuGranted" to Privs.shizukuGranted(),
        "sdkInt" to Privs.sdkInt()
      )
    }

    AsyncFunction("runCommand") { command: String ->
      val (code, out) = Privs.exec(command)
      mapOf("code" to code, "out" to out)
    }

    // Shizuku only shows its permission dialog when asked from an Activity, and
    // it is a separate one-time grant the user has to confirm.
    AsyncFunction("requestShizuku") {
      val activity = appContext.currentActivity
        ?: return@AsyncFunction mapOf("ok" to false, "reason" to "brak aktywnosci")
      try {
        Shizuku.requestPermission(0)
        mapOf("ok" to true, "granted" to Privs.shizukuGranted())
      } catch (t: Throwable) {
        mapOf(
          "ok" to false,
          "reason" to (t.message ?: "Shizuku nie jest zainstalowane albo nieuzwolnione"),
          "ping" to Privs.shizukuGranted()
        )
      }
    }

    // --- portal -----------------------------------------------------------

    AsyncFunction("portalStart") { port: Int, configJson: String ->
      portal.start(port, configJson)
    }

    AsyncFunction("portalStop") {
      portal.stop()
    }

    AsyncFunction("portalStatus") {
      mapOf(
        "running" to portal.isRunning(),
        "port" to PORT
      )
    }

    // --- barrier ----------------------------------------------------------

    AsyncFunction("blockerArm") { domains: List<String>, self: Boolean ->
      currentPorts = domains.map { it.lowercase().trim() }.filter { it.isNotBlank() }.toSet()
      blockSelf = self

      if (!Privs.isRootAvailable()) {
        return@AsyncFunction mapOf(
          "ok" to false,
          "reason" to "root required",
          "tier" to Privs.tier().name
        )
      }

      val iface = Blocker.hotspotInterface()
      if (iface == null) {
        return@AsyncFunction mapOf(
          "ok" to false,
          "reason" to "nie znaleziono interfejsu hotspota - włącz hotspot w Ustawieniach i spróbuj ponownie",
          "interfaces" to Blocker.detectInterfaces().map { it.name + "=" + it.address }
        )
      }

      sinkhole.start()
      val log = Blocker.arm(iface.name, self)
      armedIface = iface.name

      mapOf(
        "ok" to true,
        "interface" to iface.name,
        "address" to iface.address,
        "sinkhole" to sinkhole.isRunning(),
        "domains" to currentPorts.size,
        "log" to log.take(3000)
      )
    }

    AsyncFunction("blockerDisarm") {
      val log = Blocker.disarm(armedIface, blockSelf)
      sinkhole.stop()
      val was = armedIface
      armedIface = null
      mapOf("ok" to true, "interface" to was, "log" to log.take(2000))
    }

    AsyncFunction("blockerStatus") {
      val iface = Blocker.hotspotInterface()
      mapOf(
        "armed" to (armedIface != null),
        "interface" to armedIface,
        "detectedInterface" to iface?.name,
        "sinkholeRunning" to sinkhole.isRunning(),
        "domains" to currentPorts.toList(),
        "hits" to sinkhole.hits(),
        "tier" to Privs.tier().name
      )
    }

    AsyncFunction("interfaces") {
      Blocker.detectInterfaces().map { mapOf("name" to it.name, "address" to it.address, "up" to it.isUp) }
    }

    // --- hotspot ----------------------------------------------------------

    AsyncFunction("hotspotStartViaShell") { ssid: String, passphrase: String ->
      val (code, out) = Hotspot.startViaShell(ssid, passphrase)
      mapOf("ok" to (code == 0), "code" to code, "out" to out.take(1500))
    }

    AsyncFunction("hotspotStopViaShell") {
      val (code, out) = Hotspot.stopViaShell()
      mapOf("ok" to (code == 0), "code" to code, "out" to out.take(1000))
    }

    AsyncFunction("hotspotStartLocalOnly") { ssid: String, passphrase: String, promise: Promise ->
      val appContext = appContext.reactContext ?: run {
        promise.reject("ERR_NO_CONTEXT", "brak kontekstu aplikacji", null)
        return@AsyncFunction
      }
      Hotspot.startLocalOnly(
        appContext,
        ssid,
        passphrase,
        onReady = { promise.resolve(it) },
        onFail = { promise.reject("ERR_LOHS", it, null) }
      )
    }

    AsyncFunction("hotspotStatus") {
      Hotspot.status()
    }

    OnDestroy {
      runCatching {
        portal.stop()
        if (armedIface != null) Blocker.disarm(armedIface, blockSelf)
        sinkhole.stop()
      }
    }
  }

  companion object {
    private const val PORT = 8080
  }
}
