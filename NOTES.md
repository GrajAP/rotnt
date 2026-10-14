## rotn't v0.1.6

Pobierz `rotnt.apk` (uniwersalny, ~69 MB) albo `rotnt-arm64.apk` (~26 MB, na większości
telefonów).

```
https://github.com/GrajAP/rotnt/releases/latest/download/rotnt.apk
```

APK jest podpisany kluczem, którego nie ma w repo. Nie jest w Google Play, bo rotn't
prosi o uprawnienia, które sklep odrzuca — a sideloadowanie z linku jest tu częścią pomysłu.

Play Protect pokaże ostrzeżenie. To oczekiwane: aplikacja zarządza siecią i uruchamia
lokalny serwer HTTP.

### Co nowego

- **Ochrona własnego telefonu bez roota.** Lokalny filtr VPN blokuje TikToka na tym
  telefonie (12 domen). Wymaga jednorazowej zgody systemowej na VPN. Bez roota, bez
  Shizuku, bez Magiska.
- **Goście z rootem: automatyczne okienko.** Sondy captive-portal trafiają na stronę
  z zasadami i linkiem do instalacji — na każdym systemie, nie tylko na Androidzie.
- **Goście bez roota: adres wprost.** Aplikacja pokazuje adres strony powitalnej
  (`http://<adres-hotspota>:8080`) — podyktuj go gościowi.
- **Ostrzeżenie o Prywatnym DNS.** Gdy jest włączony, omija każdy lokalny filtr —
  aplikacja mówi to wprost i prowadzi do ustawień.

### Co zobaczysz

Trzy tryby, zależne od tego, co telefon potrafi. Blokada u siebie (VPN) działa w każdym.

| | uprawnienia | nazwa sieci | internet u gościa | blokada gościa |
|---|---|---|---|---|
| **T0** | żadne | nadaje Android | nie | nie |
| **T1** | Shizuku | własna | tylko gdy telefon jest na Wi-Fi | nie |
| **T2** | Magisk | własna | tak | tak |

Bez roota nie da się zablokować gościa ani nazwać hotspota z internetem — to granica
Androida, nie błąd aplikacji. Szczegóły i uczciwe ograniczenia w
[README](https://github.com/GrajAP/rotnt#uczciwe-ograniczenia).
