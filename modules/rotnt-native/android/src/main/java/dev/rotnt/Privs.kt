package dev.rotnt

import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import java.io.File

object Privs {

  sealed class Tier {
    /** T0: plain app. Local-only hotspot only. */
    object None : Tier()

    /** T1: Shizuku granted, so we can act as shell uid. */
    object Shell : Tier()

    /** T2: real root. Full barrier. */
    object Root : Tier()

    val name: String
      get() = when (this) {
        None -> "none"
        Shell -> "shell"
        Root -> "root"
      }

    /** Can we name the network and run the soft AP ourselves? */
    val canNameHotspot: Boolean get() = this != None

    /** Can we see and cut guest traffic? */
    val canBlockGuests: Boolean get() = this == Root
  }

  fun rootBinary(): String? {
    val candidates = listOf(
      "/system/bin/su",
      "/system/xbin/su",
      "/sbin/su",
      "/su/bin/su",
      "/magisk/.core/bin/su",
      "/debug_ramdisk/su"
    )
    for (p in candidates) {
      val f = File(p)
      if (f.exists() && f.canExecute()) return p
    }
    for (path in System.getenv("PATH")?.split(":") ?: emptyList()) {
      val f = File(path, "su")
      if (f.exists() && f.canExecute()) return f.absolutePath
    }
    return null
  }

  fun isRootAvailable(): Boolean {
    val bin = rootBinary() ?: return false
    return try {
      val p = ProcessBuilder(bin, "-c", "id -u").redirectErrorStream(true).start()
      val out = p.inputStream.bufferedReader().readText().trim()
      p.waitFor()
      p.exitValue() == 0 && (out == "0" || out.endsWith("uid=0"))
    } catch (t: Throwable) {
      false
    }
  }

  fun shizukuGranted(): Boolean = try {
    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
  } catch (t: Throwable) {
    false
  }

  fun tier(): Tier = when {
    isRootAvailable() -> Tier.Root
    shizukuGranted() -> Tier.Shell
    else -> Tier.None
  }

  /**
   * Runs a command as the most privileged identity available and returns
   * (exitCode, combinedOutput). Never throws.
   */
  fun exec(command: String): Pair<Int, String> {
    if (isRootAvailable()) {
      runCatching {
        val bin = rootBinary()!!
        val p = ProcessBuilder(bin, "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
      }.onFailure { return 1 to (it.message ?: "exec failed") }
    }
    if (shizukuGranted()) {
      val process = shizukuProcess(command)
        ?: return 1 to "Shizuku jest aktywny, ale nie dało się uruchomić procesu (brak metody newProcess)"
      return runCatching {
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        process.waitFor()
        process.exitValue() to out
      }.getOrElse { 1 to (it.message ?: "shizuku exec failed") }
    }
    return 126 to "no elevated identity available"
  }

  /**
   * `Shizuku.newProcess` is not public API in dev.rikka.shizuku:api:13.1.5, but the
   * sanctioned alternative (a user service + AIDL) needs a generated stub for a
   * service the Shizuku daemon has to bind to. Shizuku lives on the app classpath,
   * not the boot classpath, so reaching for it reflectively is not a hidden-API
   * violation. Swap this out for a user service if the reflection ever stops working.
   */
  private fun shizukuProcess(command: String): Process? = runCatching {
    val method = Shizuku::class.java.declaredMethods
      .firstOrNull { it.name == "newProcess" && it.parameterTypes.size == 3 }
      ?: return null
    method.isAccessible = true
    method.invoke(
      null,
      arrayOf("/system/bin/sh", "-c", command),
      arrayOf<Any>(),
      "/"
    ) as? Process
  }.getOrNull()

  fun sdkInt(): Int = Build.VERSION.SDK_INT
}
