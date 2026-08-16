'use strict';

const assert = require('assert');
const path = require('path');
const fs = require('fs');

const {
  VERSION,
  BINARY_NAME,
  EXPECTED_SHA256,
  getCacheDirectory,
  getCachedBinaryPath,
} = require('../lib/config');

const { calculateSha256, verifyBinaryIntegrity } = require('../lib/verifier');
const { formatBytes } = require('../lib/downloader');

async function runTests() {
  console.log('--- Running Local Camera Receiver CLI Tests ---\n');

  // Test 1: Config paths & constants
  console.log('Test 1: Configuration properties...');
  assert.strictEqual(typeof VERSION, 'string');
  assert.strictEqual(BINARY_NAME, 'LocalCameraReceiverSetup.exe');
  assert.strictEqual(EXPECTED_SHA256.length, 64);
  assert.ok(getCacheDirectory().includes('local-camera-receiver'));
  assert.ok(getCachedBinaryPath().endsWith('LocalCameraReceiverSetup.exe'));
  console.log('  [PASS] Configuration paths and constants valid.');

  // Test 2: formatBytes helper
  console.log('\nTest 2: Byte formatting utility...');
  assert.strictEqual(formatBytes(0), '0 B');
  assert.strictEqual(formatBytes(1024), '1.0 KB');
  assert.strictEqual(formatBytes(1048576 * 25.5), '25.5 MB');
  console.log('  [PASS] formatBytes produces expected output.');

  // Test 3: Local repository binary hash verification
  console.log('\nTest 3: Local repository binary SHA-256 validation...');
  const repoBinary = path.resolve(__dirname, '..', '..', BINARY_NAME);
  if (fs.existsSync(repoBinary)) {
    const hash = await calculateSha256(repoBinary);
    console.log(`  Calculated Hash: ${hash}`);
    console.log(`  Expected Hash:   ${EXPECTED_SHA256}`);
    assert.strictEqual(hash, EXPECTED_SHA256);

    const isValid = await verifyBinaryIntegrity(repoBinary, EXPECTED_SHA256);
    assert.strictEqual(isValid, true);
    console.log('  [PASS] Binary SHA-256 checksum matched SHA256SUMS.txt.');
  } else {
    console.log('  [SKIP] Local binary not present in test environment.');
  }

  console.log('\n=== All CLI unit tests passed successfully! ===\n');
}

runTests().catch((err) => {
  console.error('\n[FAIL] Test suite failed:', err);
  process.exit(1);
});
