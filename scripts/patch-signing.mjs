import fs from 'node:fs';
import path from 'node:path';

const target = path.resolve('android/app/build.gradle');
let gradle = fs.readFileSync(target, 'utf8');

const SIGNING_ANCHOR = `    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }`;

const SIGNING_REPLACEMENT = `    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
            storeFile file(project.findProperty('ROTNT_STORE_FILE') ?: 'rotnt.keystore')
            storePassword project.findProperty('ROTNT_STORE_PASSWORD') ?: ''
            keyAlias project.findProperty('ROTNT_KEY_ALIAS') ?: ''
            keyPassword project.findProperty('ROTNT_KEY_PASSWORD') ?: ''
        }
    }
    splits {
        abi {
            enable true
            reset()
            include 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'
            universalApk true
        }
    }`;

const RELEASE_ANCHOR = `        release {
            // Caution! In production, you need to generate your own keystore file.
            // see https://reactnative.dev/docs/signed-apk-android.
            signingConfig signingConfigs.debug`;

const RELEASE_REPLACEMENT = `        release {
            signingConfig signingConfigs.release`;

if (!gradle.includes(SIGNING_ANCHOR)) {
  console.error('patch-signing: signingConfigs anchor not found; expo template changed?');
  process.exit(1);
}
if (!gradle.includes(RELEASE_ANCHOR)) {
  console.error('patch-signing: release buildType anchor not found; expo template changed?');
  process.exit(1);
}

gradle = gradle.replace(SIGNING_ANCHOR, SIGNING_REPLACEMENT);
gradle = gradle.replace(RELEASE_ANCHOR, RELEASE_REPLACEMENT);

fs.writeFileSync(target, gradle);
console.log('patch-signing: release signing wired up');
