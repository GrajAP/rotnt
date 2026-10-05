## rotn't

Pobierz `rotnt.apk` (uniwersalny, ~69 MB) albo `rotnt-arm64.apk` (~26 MB, na większości
telefonów).

```
https://github.com/GrajAP/rotnt/releases/latest/download/rotnt.apk
```

APK jest podpisany kluczem, którego nie ma w repo. Nie jest w Google Play, bo rotn't
prosi o uprawnienia, które sklep odrzuca — a sideloadowanie z linku jest tu częścią pomysłu.

Play Protect pokaże ostrzeżenie. To oczekiwane: aplikacja zarządza siecią i uruchamia
lokalny serwer HTTP.

### Co zobaczysz

Gdy włączysz rotn't, nazwa sieci zmienia się na to, co wpiszesz w polu SSID. Gość widzi
ją **zanim się połączy** — i to jest moment, w którym decyduje.

Trzy tryby, zależne od tego, co telefon potrafi:

| | uprawnienia | nazwa sieci | internet u gościa | blokada gościa |
|---|---|---|---|---|
| **T0** | żadne | własna | nie | nie |
| **T1** | Shizuku | własna | tylko gdy telefon jest na Wi-Fi | nie |
| **T2** | Magisk | własna | tak | tak |

Szczegóły i uczciwe ograniczenia w [README](https://github.com/GrajAP/rotnt#uczciwe-ograniczenia).
