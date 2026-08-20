'use strict';

const assert = require('assert');
const path = require('path');
const fs = require('fs');

const packageJson = require('../package.json');
const {
  VERSION,
  BINARY_NAME,
  EXPECTED_SHA256,
  PUBLISHED_BINARY_COMMIT,
  PRIMARY_DOWNLOAD_URL,
  LATEST_DOWNLOAD_URL,
  getCacheDirectory,
  getCachedBinaryPath,
} = require('../lib/config');

const { calculateSha256, verifyBinaryIntegrity } = require('../lib/verifier');
const { formatBytes } = require('../lib/downloader');

const repoRoot = path.resolve(__dirname, '..', '..');

function readRepo(relativePath) {
  return fs.readFileSync(path.join(repoRoot, relativePath), 'utf8');
}

async function runTests() {
  console.log('--- Running Local Camera Receiver CLI Tests ---\n');

  console.log('Test 1: Configuration properties...');
  assert.strictEqual(VERSION, packageJson.version);
  assert.strictEqual(VERSION, '0.2.0');
  assert.strictEqual(BINARY_NAME, 'LocalCameraReceiverSetup.exe');
  assert.match(EXPECTED_SHA256, /^[0-9a-f]{64}$/);
  assert.match(PUBLISHED_BINARY_COMMIT, /^[0-9a-f]{40}$/);
  assert.ok(PRIMARY_DOWNLOAD_URL.includes(PUBLISHED_BINARY_COMMIT));
  assert.ok(LATEST_DOWNLOAD_URL.includes(PUBLISHED_BINARY_COMMIT));
  assert.ok(!PRIMARY_DOWNLOAD_URL.includes('/main/'));
  assert.ok(!PRIMARY_DOWNLOAD_URL.includes('/releases/latest/'));
  assert.ok(getCacheDirectory().includes('local-camera-receiver'));
  assert.ok(getCachedBinaryPath().endsWith('LocalCameraReceiverSetup.exe'));
  console.log('  [PASS] CLI version and immutable binary pin are valid.');

  console.log('\nTest 2: Product version consistency...');
  const manifest = JSON.parse(readRepo('pc/data/manifest.json'));
  assert.strictEqual(manifest.version, VERSION);
  assert.match(readRepo('pc/CMakeLists.txt'), new RegExp(`project\\(local_camera_receiver VERSION ${VERSION.replaceAll('.', '\\.')}`));
  assert.match(readRepo('pc/installer/LocalCameraReceiver.iss'), new RegExp(`#define AppVersion "${VERSION.replaceAll('.', '\\.')}"`));
  assert.match(readRepo('mobile/app/build.gradle.kts'), new RegExp(`versionName = "${VERSION.replaceAll('.', '\\.')}"`));
  console.log('  [PASS] Android, Windows, plugin, installer, and CLI versions agree.');

  console.log('\nTest 3: Byte formatting utility...');
  assert.strictEqual(formatBytes(0), '0 B');
  assert.strictEqual(formatBytes(1024), '1.0 KB');
  assert.strictEqual(formatBytes(1048576 * 25.5), '25.5 MB');
  console.log('  [PASS] formatBytes produces expected output.');

  console.log('\nTest 4: Canonical root binary SHA-256 validation...');
  const sums = readRepo('SHA256SUMS.txt')
    .trim()
    .split(/\r?\n/)
    .map((line) => line.trim().split(/\s+/, 2));
  const expectedRootHash = new Map(sums).get(BINARY_NAME);
  assert.match(expectedRootHash || '', /^[0-9A-Fa-f]{64}$/);

  const repoBinary = path.join(repoRoot, BINARY_NAME);
  if (fs.existsSync(repoBinary)) {
    const hash = await calculateSha256(repoBinary);
    console.log(`  Calculated Hash: ${hash}`);
    console.log(`  Root Hash:       ${expectedRootHash}`);
    assert.strictEqual(hash.toLowerCase(), expectedRootHash.toLowerCase());

    const isValid = await verifyBinaryIntegrity(repoBinary, expectedRootHash);
    assert.strictEqual(isValid, true);
    console.log('  [PASS] Root installer matches SHA256SUMS.txt.');
  } else {
    console.log('  [SKIP] Local root installer not present in test environment.');
  }

  console.log('\n=== All CLI unit tests passed successfully! ===\n');
}

runTests().catch((err) => {
  console.error('\n[FAIL] Test suite failed:', err);
  process.exit(1);
});
