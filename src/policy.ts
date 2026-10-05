export type Tier = 'none' | 'shell' | 'root';

export type Rule = {
  name: string;
  reason: string;
  blocked: boolean;
};

export const TIKTOK_DOMAINS = [
  'tiktok.com',
  'tiktokv.com',
  'tiktokcdn.com',
  'tiktokcdn-us.com',
  'tiktokrow-cdn.com',
  'tiktokd.org',
  'tiktokgl.com',
  'byteoversea.com',
  'ibytedtos.com',
  'ibyteimg.com',
  'musical.ly',
  'muscdn.com',
];

export const DEFAULT_SSID = 'rotnt \u00b7 bez brainrotu';
export const DEFAULT_PASSPHRASE = 'nienawidzetiktoka';
export const DEFAULT_INSTALL_URL =
  'https://github.com/GrajAP/rotnt/releases/latest/download/rotnt.apk';

export const RULES: Rule[] = [
  {
    name: 'TikTok',
    reason: 'scrollowanie bez celu',
    blocked: true,
  },
  {
    name: 'wszystko inne',
    reason: 'Twoja decyzja',
    blocked: false,
  },
];

export const TIER_LABEL: Record<Tier, string> = {
  none: 'T0 \u00b7 informacyjny',
  shell: 'T1 \u00b7 w\u0142asna nazwa sieci',
  root: 'T2 \u00b7 pe\u0142na bariera',
};

export const TIER_BLURB: Record<Tier, string> = {
  none: 'Bez uprawnie\u0144. Dzia\u0142a nazwa sieci i strona powitalna, ale go\u015b\u0107 m\u0119ci internetu nie ograniczymy.',
  shell:
    'Masz Shizuku, wi\u0119c rotnt przejmuje nazw\u0119 twojej sieci. Go\u015b\u0107 nadal nie ma bariery \u2014 do tego potrzeba roota.',
  root:
    'Pe\u0142na bariera. Ruch go\u015bci idzie przez nasz serwer DNS, kt\u00f3ry blokuje wybrane domeny dla ka\u017cdego klienta hotspota.',
};
