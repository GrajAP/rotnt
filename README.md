# rotn't

Aplikacja, która zamienia hotspot w telefonu w sieć z zasadami. Zamiast
kradnieć Ci dane, mówi wprost co jest zablokowane — i robi to samo na
Twoim telefonie.

Nie ma tu serwera. Cała logika działa lokalnie na telefonie, a jedynym
zewnętrznym adresem jest link do pobrania tej aplikacji.

## Tryby pracy

Android daje aplikacji innej niż systemowa bardzo mało władzy nad hotspotem.
Dlatego rotn't ma trzy tryby i sam wykrywa, który jest możliwy:

| | uprawnienia | nazwa sieci | internet u gościa | blokada gościa |
|---|---|---|---|---|
| **T0** | żadne | ✗ Android nadaje własną | nie (local-only hotspot) | nie |
| **T1** | Shizuku | ✓ własna | tylko gdy telefon sam jest na Wi-Fi | nie |
| **T2** | Magisk (root) | ✓ własna | tak | tak |

### T0 — bez żadnych uprawnień

**Nazwy sieci nie da się ustawić.** To nie jest ograniczenie rotn't, tylko
Androida. W publicznym SDK `SoftApConfiguration.Builder` ma dokładnie dwie
metody: `setChannels` i `build`. `setWifiSsid` i `setPassphrase` są `@hide`,
a klasy `WifiSsid` w ogóle nie ma w publicznym `android.jar`. Trzecia
aplikacja nie może nazwać hotspota żadną ścieżką.

Jedyne publiczne przeciążenie to `startLocalOnlyHotspot(callback, handler)`,
które ignoruje nazwę i pozwala Androidowi wybrać własną — w praktyce
`AndroidShare_2626`. Aplikacja odczytuje faktycznie nadaną nazwę i
pokazuje ją w statusie, zamiast udawać, że ustawiła Twoją.

Co T0 realnie daje: uruchomiony hotspot, strona powitalna na 8080, zasady
oraz link do instalacji rotn't dla gościa. Bez internetu dla gościa.
Manifest informacyjny, nie bariera.

Jeśli chcesz mieć własną nazwę bez roota: wpisz ją ręcznie w Ustawieniach
w „Hotspot osobisty" (Android pozwala), albo daj Shizuku i T1.

### T1 — Shizuku, bez roota

`cmd wifi start-softap <ssid> <wpa2> <hasło>` wykonuje się jako uid shell,
a Shizuku daje aplikacji ten uid bez roota. Więc rotn't naprawdę przejmuje
nazwę Twojej sieci.

Instalacja: zainstaluj [Shizuku](https://shizuku.dev), uruchom przez
wireless debugging raz i przyznaj uprawnienia rotn't. Potem włącz T1 w aplikacji.

Wada: ta ścieżka nie włącza udostępniania internetu. Internet działa tylko
jeśli telefon sam jest połączony z Wi-Fi.

### T2 — root

Pełna bariera. Ruch DNS gości jest przekierowywany do wbudowanego serwera
w aplikacji, który zwraca `0.0.0.0` dla zablokowanych domen. Reguły iptables
siedzą w osobnej łańcuchu (`ROTNT`), więc zdejmowanie bariery to jedno
usunięcie i nic nie zostaje w połowie zmienione.

## Instalacja

APK jest podpisany kluczem, którego nie ma w repo. Nie jest w Google Play,
bo rotn't prosi o uprawnienia, które sklep odrzuca, a sideloadowanie
z publicznego linku jest tu częścią pomysłu.

```
https://github.com/GrajAP/rotnt/releases/latest/download/rotnt.apk
```

Play Protect pokaże ostrzeżenie. To oczekiwane — aplikacja zarządza siecią
i uruchamia lokalny serwer HTTP. Przy pierwszym uruchomieniu włącz
zainstalowanie z nieznanych źródeł dla tej aplikacji.

## Uczciwe ograniczenia

- **Bez roota nie da się zablokować gościa.** To nie jest bug w rotn't —
  `VpnService` przechwytuje ruch tylko z urządzeń, które same uruchomiły apkę.
  Pakiety klientów tetheringu idą z `wlan0` przez NAT i do VpnService nie trafiają.
- **DNS-over-HTTPS działa.** Blokada po domenie nie widzi ruchu zaszytego
  w TLS na porcie 443. To dziura, nie rozwiązanie. Wykrywanie DoH wymaga
 MITM, którego rotn't świadomie nie robi.
- **Portal na 8080 łapie tylko Androida.** Gość z iOS, macOS i Windows
  sprawdza port 80, którego bez roota nie zajmiemy.
- **Nie da się zmienić nazwy sieci bez Shizuku lub roota.** Powyżej dlaczego.
- **Który interfejs to hotspot** jest zgadywane. Android nazywa go
  `wlan1`, `ap0`, `swlan0` lub `ap+wlan0` zależnie od wersji i producenta.
  Aplikacja czyta `ip addr` i ocenia kandydatów. Na nietypowym urządzeniu
  może nie trafić — wtedy bariera się nie włączy i zobaczysz to w logu.
- **Aplikacja nie instaluje się sama na cudzym telefonie.** Nikt tego nie
  zrobi, nawet gdybyśmy tego chcieli.

## Jak to działa

```
gość --UDP:53--> PREROUTING (iptables REDIRECT) --> :5353 DnsSinkhole
                                                      |
                                            domena na liście? -> 0.0.0.0
                                            w przeciwnym razie -> 1.1.1.1/9.9.9.9/8.8.8.8
```

Świadomie **bez MITM**. Nie dekryptujemy ruchu HTTPS, nie podpisujemy się
certyfikatem, nie przekierowujemy na fałszywe strony. Blokada po domenie
jest nieprecyzyjna i to jest cena za nieingerowanie w treść komunikacji.

Portal to osobny serwer HTTP na 8080, który odpowiada `302` na
`/generate_204`, co wywołuje systemowy arkusz „zaloguj się do tej sieci".
Strona pokazuje reguły i link do instalacji rotn't.

## Build

Wymaga JDK 17 i Android SDK.

```bash
npm install
npx expo prebuild --platform android --no-install
node scripts/patch-signing.mjs
echo "$ROTNT_KEYSTORE_B64" | base64 -d > android/app/rotnt.keystore
cd android
./gradlew :app:assembleRelease \
  -PROTNT_STORE_FILE=rotnt.keystore \
  -PROTNT_STORE_PASSWORD="$ROTNT_STORE_PASSWORD" \
  -PROTNT_KEY_ALIAS=rotnt \
  -PROTNT_KEY_PASSWORD="$ROTNT_KEY_PASSWORD"
```

Release z tagiem buduje się w GitHub Actions i ląduje w Releases.

## Struktura

```
App.tsx                        interfejs
src/policy.ts                  lista domen i etykiety trybów
modules/rotnt-native/          moduł natywny (Kotlin)
  Privs.kt                     root / Shizuku / uid shell
  DnsSinkhole.kt               serwer DNS blokujący
  Blocker.kt                   iptables + wykrywanie interfejsu
  PortalServer.kt              serwer HTTP na 8080
  Hotspot.kt                   uruchamianie softapu
scripts/patch-signing.mjs      podpisywanie release + podział po ABI
```

## Licencja

MIT
