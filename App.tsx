import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Platform,
  Pressable,
  ScrollView,
  StatusBar as RNStatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { Rotnt, capabilities, isAvailable } from './src/native';
import { ensureHotspotPermissions } from './src/permissions';
import {
  DEFAULT_INSTALL_URL,
  DEFAULT_PASSPHRASE,
  DEFAULT_SSID,
  RULES,
  TIER_BLURB,
  TIER_LABEL,
  TIKTOK_DOMAINS,
} from './src/policy';

type Caps = Awaited<ReturnType<typeof capabilities>>;
type Log = { id: number; text: string; bad?: boolean };

export default function App() {
  const [caps, setCaps] = useState<Caps | null>(null);
  const [ssid, setSsid] = useState(DEFAULT_SSID);
  const [pass, setPass] = useState(DEFAULT_PASSPHRASE);
  const [installUrl, setInstallUrl] = useState(DEFAULT_INSTALL_URL);

  const [on, setOn] = useState(false);
  const [busy, setBusy] = useState(false);
  const [portalUp, setPortalUp] = useState(false);
  const [armed, setArmed] = useState(false);
  const [iface, setIface] = useState<string | null>(null);
  const [hits, setHits] = useState<Record<string, number>>({});
  const [log, setLog] = useState<Log[]>([]);
  const [hotspotNote, setHotspotNote] = useState<string | null>(null);
  const [permsGranted, setPermsGranted] = useState<boolean | null>(null);
  const [actualSsid, setActualSsid] = useState<string | null>(null);
  const [shizukuBusy, setShizukuBusy] = useState(false);

  let logId = useMemo(() => ({ n: 0 }), []);

  const say = useCallback((text: string, bad = false) => {
    logId.n += 1;
    const id = logId.n;
    // Mirror into logcat too. State-only logging meant that anything the app
    // reported was invisible over adb, so failures were impossible to diagnose.
    console.log(`[rotnt] ${bad ? 'ERR ' : ''}${text}`);
    setLog((prev) => [{ id, text, bad }, ...prev].slice(0, 40));
  }, []);

  const refreshCaps = useCallback(async () => {
    const c = await capabilities();
    setCaps(c);
    return c;
  }, []);

  useEffect(() => {
    refreshCaps();
    Rotnt.blockerStatus().then((s: any) => {
      setArmed(!!s?.armed);
      setIface(s?.interface ?? null);
      setHits(s?.hits ?? {});
    });
    Rotnt.portalStatus().then((s: any) => setPortalUp(!!s?.running));
  }, [refreshCaps]);

  const poll = useCallback(async () => {
    const s: any = await Rotnt.blockerStatus();
    setArmed(!!s?.armed);
    setIface(s?.interface ?? null);
    setHits(s?.hits ?? {});
  }, []);

  useEffect(() => {
    if (!on) return;
    const t = setInterval(poll, 1500);
    return () => clearInterval(t);
  }, [on, poll]);

  const portalConfig = useMemo(
    () =>
      JSON.stringify({
        appName: 'rotnt',
        owner: 'wlasciciel',
        ssid,
        tierLabel: caps ? TIER_LABEL[caps.tier] : TIER_LABEL.none,
        installUrl,
        note: caps
          ? TIER_BLURB[caps.tier]
          : 'Reguly ustawiasz sam, na swoim telefonie.',
        rules: RULES,
      }),
    [caps, ssid, installUrl]
  );

  async function start() {
    setBusy(true);
    say('wlaczam rotnt');
    try {
      const c = await refreshCaps();

      const portal: any = await Rotnt.portalStart(8080, portalConfig);
      setPortalUp(!!portal?.ok);
      say(portal?.ok ? `portal slucha na 8080` : `portal: ${portal?.error ?? 'blad'}`, !portal?.ok);

      const perms = await ensureHotspotPermissions();
      setPermsGranted(perms.granted);
      if (perms.denied.length > 0) {
        say('bez uprawnienia do Wi-Fi nie zmienie nazwy sieci', true);
      }

      // T1/T2: we name and own the network. T0: local-only, no internet.
      if (c.canNameHotspot) {
        const hs: any = await Rotnt.hotspotStartViaShell(ssid, pass);
        say(
          hs?.ok
            ? `hotspot wlaczony jako "${ssid}"`
            : `hotspot (shell) blad: ${String(hs?.out ?? '').trim().slice(0, 160)}`,
          !hs?.ok
        );
        setHotspotNote(
          hs?.ok
            ? 'Uwaga: tryb shell nie wlacza udostepniania internetu. Dziala, gdy telefon sam jest na Wi-Fi.'
            : null
        );
} else if (perms.granted) {
        let locallyStarted = false;
        try {
          const loh: any = await Promise.race([
            Rotnt.hotspotStartLocalOnly(ssid, pass),
            new Promise((_, reject) =>
              setTimeout(() => reject(new Error('timeout 15s')), 15000)
            ),
          ]).then((v: any) => ((locallyStarted = true), v));

          const actual = loh?.actualSsid;
          setActualSsid(actual ?? null);
          if (actual && actual !== ssid) {
            say(`hotspot wystartowal, ale Android dal mu nazwe "${actual}"`);
            setHotspotNote(
              `Android wybral nazwe sam: "${actual}". Zmienic jej nie da sie bez Shizuku lub roota — ` +
              `setWifiSsid w SoftApConfiguration jest ukrytym API. Zmien nazwe recznie w Ustawieniach, ` +
              `albo daj Shizuku, zeby rotnt przejal hotspot.`
            );
          } else {
            say(`hotspot local-only: ${actual ?? 'uruchomiony'}`);
            setHotspotNote('Local-only: goscie widza strone, ale nie ma internetu.');
          }
        } catch (e: any) {
          say(`local-only hotspot odrzucony: ${e?.message ?? e}`, true);
          setHotspotNote(null);
        }
        if (!locallyStarted) {
          say('hotspot nie odpowiedzial w 15s', true);
        }
      } else {
        say('pomijam naziwywanie sieci: brak uprawnienia Wi-Fi', true);
      }

      if (c.canBlockGuests) {
        const arm: any = await Rotnt.blockerArm(TIKTOK_DOMAINS, true);
        setArmed(!!arm?.ok);
        setIface(arm?.interface ?? null);
        say(
          arm?.ok
            ? `bariera wlaczona na ${arm.interface} (${TIKTOK_DOMAINS.length} domen)`
            : `bariera: ${arm?.reason ?? 'blad'}`,
          !arm?.ok
        );
      } else {
        say('bariera wymaga roota (Magisk). Bez niego to informacja, nie blokada.', true);
      }

      setOn(true);
    } finally {
      setBusy(false);
    }
  }

  async function askShizuku() {
    setShizukuBusy(true);
    try {
      const res: any = await Rotnt.requestShizuku();
      if (!res?.ok) {
        say(`Shizuku: ${res?.reason ?? 'blad'}`, true);
        return;
      }
      // The dialog is asynchronous; give the user a moment to answer it.
      for (let i = 0; i < 12; i++) {
        await new Promise((r) => setTimeout(r, 1000));
        const c = await capabilities();
        if (c.shizukuGranted) {
          setCaps(c);
          say('Shizuku przyznane: rotnt moze nadawac nazwe sieci');
          return;
        }
      }
      say('Shizuku nadal nieprzyznane - odrzucono dialog?', true);
    } finally {
      setShizukuBusy(false);
    }
  }

  async function stop() {
    setBusy(true);
    try {
      const disarm: any = await Rotnt.blockerDisarm();
      say(disarm?.ok ? 'bariera zdjeta' : 'bariera nie zdejta', !disarm?.ok);
      await Rotnt.portalStop();
      setPortalUp(false);
      setOn(false);
      const hs: any = await Rotnt.hotspotStopViaShell();
      if (hs?.ok) say('hotspot wylaczony');
      await poll();
    } finally {
      setBusy(false);
    }
  }

  const tier = caps?.tier ?? 'none';
  const totalHits = Object.values(hits).reduce((a, b) => a + b, 0);

  return (
    <View style={s.root}>
      <StatusBar style="light" />
      <ScrollView contentContainerStyle={s.scroll} keyboardShouldPersistTaps="handled">
        <View style={s.header}>
          <Text style={s.wordmark}>rotn't</Text>
          <View style={[s.badge, tier === 'root' && s.badgeRoot, tier === 'shell' && s.badgeShell]}>
            <Text style={s.badgeText}>{caps ? TIER_LABEL[tier] : 'sprawdzam...'}</Text>
          </View>
        </View>

        <Text style={s.lede}>{TIER_BLURB[tier]}</Text>

        {!isAvailable && (
          <View style={s.warn}>
            <Text style={s.warnText}>
              Modul natywny nie jest zaladowany. Uruchom przez wersje zbudowaną ze zrodla, nie
              przez Expo Go.
            </Text>
          </View>
        )}

        <Section title="Nazwa sieci">
          <Field label="SSID" value={ssid} onChangeText={setSsid} />
          <Field label="Haslo" value={pass} onChangeText={setPass} secure />
          <Text style={s.hint}>
            SSID to jedyne, co dziala na kazdym telefonie bez zadnych uprawnien. Gość widzi te
            dwie linijki zanim cokolwiek polaczy.
          </Text>
        </Section>

        <Pressable
          onPress={on ? stop : start}
          disabled={busy}
          style={({ pressed }) => [
            s.cta,
            on && s.ctaOff,
            pressed && s.ctaPressed,
            busy && s.ctaBusy,
          ]}
        >
          {busy ? (
            <ActivityIndicator color="#0d1117" />
          ) : (
            <Text style={s.ctaText}>{on ? 'wylacz rotnt' : 'wlacz rotnt'}</Text>
          )}
        </Pressable>

        {hotspotNote ? <Text style={s.note}>{hotspotNote}</Text> : null}

        <Section title="Stan">
          <StatusRow label="strona powitalna (8080)" value={portalUp ? 'aktywna' : 'wylaczona'} ok={portalUp} />
          {actualSsid && (
            <StatusRow label="nazwa sieci w telefonie" value={actualSsid} ok={actualSsid === ssid} />
          )}
          <StatusRow
            label="bariera dla gosci"
            value={armed ? `aktywna na ${iface}` : tier === 'root' ? 'wylaczona' : 'wymaga roota'}
            ok={armed}
          />
          {totalHits > 0 && (
            <StatusRow label="zablokowane zapytania" value={String(totalHits)} ok={false} />
          )}
          {Object.keys(hits).length > 0 && (
            <View style={s.hits}>
              {Object.entries(hits)
                .sort((a, b) => b[1] - a[1])
                .slice(0, 6)
                .map(([domain, n]) => (
                  <Text key={domain} style={s.hit}>
                    {n}× {domain}
                  </Text>
                ))}
            </View>
          )}
        </Section>

        {tier === 'none' && !permsGranted && (
          <Section title="Tryb T1 (Shizuku)">
            <Text style={s.hint}>
              Shizuku pozwala rotnt nadawac wlasna nazwe twojej sieci hotspotu, bez roota.
              Zainstaluj Shizuku, uruchom go przez wireless debugging, a potem nacisnij.
            </Text>
            <Pressable
              onPress={askShizuku}
              disabled={shizukuBusy}
              style={({ pressed }) => [s.smallBtn, pressed && { opacity: 0.8 }, shizukuBusy && { opacity: 0.6 }]}
            >
              {shizukuBusy ? (
                <ActivityIndicator color="#e6edf3" />
              ) : (
                <Text style={s.smallBtnText}>popros Shizuku o uprawnienia</Text>
              )}
            </Pressable>
          </Section>
        )}

        <Section title="Reguly">
          {RULES.map((r) => (
            <View key={r.name} style={[s.rule, !r.blocked && s.ruleOn]}>
              <Text style={[s.ruleMark, !r.blocked && s.ruleMarkOn]}>
                {r.blocked ? '×' : '✓'}
              </Text>
              <Text style={s.ruleName}>{r.name}</Text>
              <Text style={s.ruleWhy}>{r.reason}</Text>
            </View>
          ))}
        </Section>

        <Section title="Link dla gosci">
          <Field label="URL instalacji" value={installUrl} onChangeText={setInstallUrl} />
          <Text style={s.hint}>
            Ten link trafia na strone powitalna. Wskazuje na plik APK w GitHub Releases.
          </Text>
        </Section>

        {log.length > 0 && (
          <Section title="Dziennik">
            <View style={s.log}>
              {log.map((l) => (
                <Text key={l.id} style={[s.logLine, l.bad && s.logBad]}>
                  {l.text}
                </Text>
              ))}
            </View>
          </Section>
        )}

        <Text style={s.footer}>
          rotn't · mz MVP · {Platform.OS} · logika bariery: DNS + iptables, zero MITM
        </Text>
      </ScrollView>
    </View>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={s.section}>
      <Text style={s.sectionTitle}>{title}</Text>
      <View style={s.card}>{children}</View>
    </View>
  );
}

function Field({
  label,
  value,
  onChangeText,
  secure,
}: {
  label: string;
  value: string;
  onChangeText: (t: string) => void;
  secure?: boolean;
}) {
  return (
    <View style={s.field}>
      <Text style={s.fieldLabel}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        style={s.input}
        secureTextEntry={secure}
        autoCapitalize="none"
        autoCorrect={false}
        placeholderTextColor="#6e7681"
      />
    </View>
  );
}

function StatusRow({ label, value, ok }: { label: string; value: string; ok: boolean }) {
  return (
    <View style={s.statusRow}>
      <View style={[s.dot, { backgroundColor: ok ? '#3fb950' : '#484f58' }]} />
      <Text style={s.statusLabel}>{label}</Text>
      <Text style={s.statusValue}>{value}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0d1117' },
  scroll: { padding: 20, paddingBottom: 64, paddingTop: 24 + (RNStatusBar.currentHeight ?? 0) },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  wordmark: { color: '#e6edf3', fontSize: 34, fontWeight: '800', letterSpacing: -0.5 },
  badge: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 20,
    backgroundColor: '#21262d',
    borderWidth: 1,
    borderColor: '#30363d',
  },
  badgeShell: { borderColor: '#d29922' },
  badgeRoot: { borderColor: '#3fb950' },
  badgeText: { color: '#adbac7', fontSize: 11, fontWeight: '600' },
  lede: { color: '#adbac7', fontSize: 15, lineHeight: 22, marginTop: 14, marginBottom: 22 },
  warn: {
    backgroundColor: '#2d1f0b',
    borderColor: '#9e6a03',
    borderWidth: 1,
    borderRadius: 10,
    padding: 14,
    marginBottom: 20,
  },
  warnText: { color: '#e3b341', fontSize: 13, lineHeight: 19 },
  section: { marginBottom: 22 },
  sectionTitle: {
    color: '#7d8590',
    fontSize: 11,
    textTransform: 'uppercase',
    letterSpacing: 0.09,
    marginBottom: 9,
    fontWeight: '600',
  },
  card: {
    backgroundColor: '#161b22',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#30363d',
    padding: 16,
  },
  field: { marginBottom: 14 },
  fieldLabel: { color: '#7d8590', fontSize: 12, marginBottom: 6 },
  input: {
    backgroundColor: '#0d1117',
    borderWidth: 1,
    borderColor: '#30363d',
    borderRadius: 8,
    paddingHorizontal: 13,
    paddingVertical: 11,
    color: '#e6edf3',
    fontSize: 15,
  },
  hint: { color: '#6e7681', fontSize: 12, lineHeight: 18 },
  cta: {
    backgroundColor: '#238636',
    borderRadius: 12,
    paddingVertical: 18,
    alignItems: 'center',
    marginBottom: 8,
  },
  ctaOff: { backgroundColor: '#6e7681' },
  ctaPressed: { opacity: 0.8 },
  ctaBusy: { opacity: 0.6 },
  ctaText: { color: '#ffffff', fontSize: 16, fontWeight: '700' },
  note: { color: '#d29922', fontSize: 12, lineHeight: 18, marginBottom: 18 },
  statusRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 7 },
  dot: { width: 8, height: 8, borderRadius: 4, marginRight: 10 },
  statusLabel: { color: '#adbac7', fontSize: 14, flex: 1 },
  statusValue: { color: '#e6edf3', fontSize: 14, fontWeight: '600' },
  hits: { marginTop: 10, borderTopWidth: 1, borderTopColor: '#30363d', paddingTop: 10 },
  hit: { color: '#ff7b72', fontSize: 12, fontFamily: Platform.OS === 'android' ? 'monospace' : 'Menlo' },
  rule: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#0d1117',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  ruleOn: { backgroundColor: '#0d1a12' },
  ruleMark: {
    color: '#ff7b72',
    fontSize: 16,
    fontWeight: '700',
    width: 22,
  },
  ruleMarkOn: { color: '#3fb950' },
  ruleName: { color: '#e6edf3', fontSize: 14, fontWeight: '600', flex: 1 },
  ruleWhy: { color: '#6e7681', fontSize: 12, maxWidth: '50%', textAlign: 'right' },
  smallBtn: {
    marginTop: 14,
    backgroundColor: '#21262d',
    borderWidth: 1,
    borderColor: '#30363d',
    borderRadius: 8,
    paddingVertical: 13,
    alignItems: 'center',
  },
  smallBtnText: { color: '#e6edf3', fontSize: 14, fontWeight: '600' },
  log: { backgroundColor: '#0d1117', borderRadius: 8, padding: 12 },
  logLine: {
    color: '#8b949e',
    fontSize: 11,
    fontFamily: Platform.OS === 'android' ? 'monospace' : 'Menlo',
    lineHeight: 17,
  },
  logBad: { color: '#d29922' },
  footer: { color: '#484f58', fontSize: 11, marginTop: 8, textAlign: 'center' },
});
