import { PermissionsAndroid, Platform } from 'react-native';

type Outcome = { granted: boolean; asked: string[]; denied: string[] };

/**
 * The local-only hotspot is the whole point of tier T0, and on Android 13+
 * startLocalOnlyHotspot throws SecurityException unless NEARBY_WIFI_DEVICES has
 * been granted at runtime. Declaring it in the manifest is not enough.
 */
export async function ensureHotspotPermissions(): Promise<Outcome> {
  const asked: string[] = [];
  const denied: string[] = [];

  if (Platform.OS !== 'android') return { granted: true, asked, denied };

  const permissions =
    Number(Platform.Version) >= 33
      ? [PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES]
      : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION];

  for (const permission of permissions) {
    const already = await PermissionsAndroid.check(permission);
    if (already) continue;
    asked.push(permission);
    const result = await PermissionsAndroid.request(permission, {
      title: 'rotnt potrzebuje dostępu do Wi-Fi',
      message:
        'Aplikacja musi nadać własną nazwę sieci hotspotu i pokazać gościom zasady tej sieci.',
      buttonPositive: 'Daj dostęp',
      buttonNegative: 'Nie',
    });
    if (result !== PermissionsAndroid.RESULTS.GRANTED) denied.push(permission);
  }

  return { granted: denied.length === 0, asked, denied };
}
