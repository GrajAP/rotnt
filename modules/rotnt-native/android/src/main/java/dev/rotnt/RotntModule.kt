package dev.rotnt

import android.content.Intent
import android.net.VpnService
import android.provider.Settings
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
      val ctx = appContext.reactContext
      mapOf(
        "tier" to tier.name,
        "canNameHotspot" to tier.canNameHotspot,
        "canBlockGuests" to tier.canBlockGuests,
        "rootAvailable" to Privs.isRootAvailable(),
        "shizukuGranted" to Privs.shizukuGranted(),
        "shizukuInstalled" to (ctx?.let { Privs.shizukuInstalled(it) } ?: false),
        "shizukuRunning" to Privs.shizukuRunning(),
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
      // Captive-portal probe hosts resolve to the gateway so guests get the
      // rules page automatically. Cleared again on disarm.
      sinkhole.setPortal(iface.address)
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
      sinkhole.setPortal(null)
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

    // --- self protection (no root: local VPN DNS filter) --------------------

    AsyncFunction("vpnPrepare") {
      val ctx = appContext.reactContext
        ?: return@AsyncFunction mapOf("needsConsent" to true, "reason" to "brak kontekstu")
      mapOf("needsConsent" to (VpnService.prepare(ctx) != null))
    }

    // The system VPN dialog must be started from an activity context flow.
    // We fire it and let JS poll vpnPrepare: once it returns needsConsent=false
    // the user has granted (or the grant already existed).
    AsyncFunction("vpnOpenConsent") {
      val ctx = appContext.reactContext
        ?: return@AsyncFunction mapOf("ok" to false, "reason" to "brak kontekstu")
      val intent = VpnService.prepare(ctx)
        ?: return@AsyncFunction mapOf("ok" to true, "already" to true)
      return@AsyncFunction try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        mapOf("ok" to true, "already" to false)
      } catch (t: Throwable) {
        mapOf("ok" to false, "reason" to (t.message ?: "nie udalo sie otworzyc zgody"))
      }
    }

    AsyncFunction("vpnStart") { domains: List<String> ->
      val ctx = appContext.reactContext
        ?: return@AsyncFunction mapOf("ok" to false, "reason" to "brak kontekstu")
      if (VpnService.prepare(ctx) != null) {
        return@AsyncFunction mapOf("ok" to false, "reason" to "consent-required")
      }
      SelfVpnService.domains = domains.map { it.lowercase().trim() }
        .filter { it.isNotBlank() }.toSet()
      SelfVpnService.resetHits()
      return@AsyncFunction try {
        val intent = Intent(ctx, SelfVpnService::class.java)
          .setAction(SelfVpnService.ACTION_START)
        ctx.startService(intent)
        mapOf("ok" to true)
      } catch (t: Throwable) {
        mapOf("ok" to false, "reason" to (t.message ?: "nie udalo sie wlaczyc VPN"))
      }
    }

    AsyncFunction("vpnStop") {
      val ctx = appContext.reactContext
      if (ctx != null) {
        runCatching {
          ctx.startService(
            Intent(ctx, SelfVpnService::class.java).setAction(SelfVpnService.ACTION_STOP)
          )
        }
      } else {
        SelfVpnService.domains = emptySet()
      }
      mapOf("ok" to true)
    }

    AsyncFunction("vpnStatus") {
      mapOf(
        "running" to SelfVpnService.running,
        "hits" to SelfVpnService.snapshotHits(),
        "domains" to SelfVpnService.domains.size
      )
    }

    // Private DNS (DoT) bypasses every local DNS filter by design. The app
    // cannot read or change it silently; it can only warn.
    AsyncFunction("privateDnsMode") {
      val ctx = appContext.reactContext
      val mode = ctx?.let {
        runCatching {
          // Literal key, not Settings.Global.PRIVATE_DNS_MODE: the constant
          // is missing from stripped platform stubs, the wire value
          // "private_dns_mode" is stable since API 28.
          Settings.Global.getString(it.contentResolver, "private_dns_mode")
        }.getOrNull()
      }
      mapOf("mode" to mode)
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
