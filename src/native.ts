import { NativeModules } from 'react-native';
import { Platform } from 'react-native';

type Any = any;

const unavailable = () => ({});

const Native: Any =
  Platform.OS === 'android' && NativeModules?.Rotnt
    ? NativeModules.Rotnt
    : null;

const stub = {
  capabilities: async () => ({ tier: 'none', canNameHotspot: false, canBlockGuests: false, rootAvailable: false, shizukuGranted: false, sdkInt: 0 }),
  runCommand: async (c: string) => ({ code: 126, out: 'unavailable' }),
  portalStart: async () => ({ ok: false, error: 'unavailable' }),
  portalStop: async () => ({ ok: true }),
  portalStatus: async () => ({ running: false, port: 8080 }),
  blockerArm: async () => ({ ok: false, reason: 'unavailable' }),
  blockerDisarm: async () => ({ ok: true }),
  blockerStatus: async () => ({ armed: false, hits: {}, tier: 'none' }),
  interfaces: async () => [],
  hotspotStartViaShell: async () => ({ ok: false, code: 126, out: 'unavailable' }),
  hotspotStopViaShell: async () => ({ ok: false, code: 126, out: 'unavailable' }),
  hotspotStartLocalOnly: async () => ({ ssid: null }),
  hotspotStatus: async () => ({ hotspotInterface: null, hotspotAddress: null, raw: '' }),
};

export const isAvailable = Native !== null;

export const Rotnt: Any = Native ?? new Proxy(stub, { get: unavailable });

export async function capabilities() {
  return (await Rotnt.capabilities()) as {
    tier: 'none' | 'shell' | 'root';
    canNameHotspot: boolean;
    canBlockGuests: boolean;
    rootAvailable: boolean;
    shizukuGranted: boolean;
    sdkInt: number;
  };
}
