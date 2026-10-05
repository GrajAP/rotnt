import { requireOptionalNativeModule } from 'expo-modules-core';
import { Platform } from 'react-native';

type Any = any;

/**
 * Everything rotnt can do, with a working no-op behind it. Used on iOS, on web,
 * and whenever the native module failed to register, so the UI can render and
 * say what is missing instead of throwing during the first render.
 */
const stub = {
  capabilities: async () => ({
    tier: 'none',
    canNameHotspot: false,
    canBlockGuests: false,
    rootAvailable: false,
    shizukuGranted: false,
    sdkInt: 0,
  }),
  runCommand: async () => ({ code: 126, out: 'unavailable' }),
  requestShizuku: async () => ({ ok: false, reason: 'unavailable' }),
  portalStart: async () => ({ ok: false, error: 'unavailable' }),
  portalStop: async () => ({ ok: true }),
  portalStatus: async () => ({ running: false, port: 8080 }),
  blockerArm: async () => ({ ok: false, reason: 'unavailable' }),
  blockerDisarm: async () => ({ ok: true }),
  blockerStatus: async () => ({ armed: false, interface: null, hits: {}, tier: 'none' }),
  interfaces: async () => [],
  hotspotStartViaShell: async () => ({ ok: false, code: 126, out: 'unavailable' }),
  hotspotStopViaShell: async () => ({ ok: false, code: 126, out: 'unavailable' }),
  hotspotStartLocalOnly: async () => ({ ssid: null }),
  hotspotStatus: async () => ({ hotspotInterface: null, hotspotAddress: null, raw: '' }),
};

/**
 * Kotlin Module definitions live in the Expo module registry, not in React
 * Native's NativeModules. Looking them up in NativeModules returns undefined
 * and every call site blows up, so go through expo-modules-core.
 */
const native =
  Platform.OS === 'android' ? (requireOptionalNativeModule<Any>('Rotnt') as Any) : null;

export const isAvailable = native != null;

export const Rotnt: Any =
  native ??
  new Proxy(stub, {
    // Delegate to the stub's real methods. Returning a fresh {} for every
    // property looked like a reasonable fallback and instead made every
    // Rotnt.something() throw "Object is not a function" at render time.
    get: (target, prop) => (target as Any)[prop],
  });

export async function capabilities(): Promise<{
  tier: 'none' | 'shell' | 'root';
  canNameHotspot: boolean;
  canBlockGuests: boolean;
  rootAvailable: boolean;
  shizukuGranted: boolean;
  sdkInt: number;
}> {
  return Rotnt.capabilities();
}
