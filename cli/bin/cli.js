#!/usr/bin/env node
'use strict';

const path = require('path');
const fs = require('fs');

const {
  VERSION,
  BINARY_NAME,
  EXPECTED_SHA256,
  PRIMARY_DOWNLOAD_URL,
  LATEST_DOWNLOAD_URL,
  getCachedBinaryPath,
  getCacheDirectory,
} = require('../lib/config');

const { verifyBinaryIntegrity, calculateSha256 } = require('../lib/verifier');
const { downloadFile, formatBytes } = require('../lib/downloader');
const { launchBinary } = require('../lib/launcher');

const HELP_TEXT = `
Local Camera Receiver CLI (v${VERSION})
=======================================
Easily download and launch Local Camera Receiver for OBS Studio on Windows.

Usage:
  npx local-camera-receiver [options] [-- <extra-args>]
  npm install -g local-camera-receiver && local-camera-receiver [options]

Options:
  -h, --help            Show this help text
  -v, --version         Show package and binary version
  -s, --silent          Run installer in silent / unattended mode
      --download-only   Download and verify binary without launching
      --clean           Delete cached binary and exit
      --force-download  Re-download the binary even if already cached
      --show-qr         Launch the pairing QR code helper directly
      --custom-url <url> Download from a custom binary URL

Examples:
  npx local-camera-receiver
  npx local-camera-receiver --silent
  npx local-camera-receiver --show-qr
  npx local-camera-receiver --clean
`;

async function main() {
  const args = process.argv.slice(2);

  // Handle help
  if (args.includes('-h') || args.includes('--help')) {
    console.log(HELP_TEXT);
    process.exit(0);
  }

  // Handle version
  if (args.includes('-v') || args.includes('--version')) {
    console.log(`local-camera-receiver CLI v${VERSION}`);
    console.log(`Target binary: ${BINARY_NAME} (SHA-256: ${EXPECTED_SHA256})`);
    process.exit(0);
  }

  // Handle clean cache
  if (args.includes('--clean')) {
    const cachedPath = getCachedBinaryPath();
    if (fs.existsSync(cachedPath)) {
      fs.unlinkSync(cachedPath);
      console.log(`[x] Deleted cached binary: ${cachedPath}`);
    } else {
      console.log(`[i] No cached binary found at: ${cachedPath}`);
    }
    process.exit(0);
  }

  // OS Check
  if (process.platform !== 'win32') {
    console.warn(`\n[!] Notice: Local Camera Receiver is designed for Windows 10 / 11 (64-bit) running OBS Studio.`);
    console.warn(`    Detected platform: ${process.platform}`);
    console.warn(`    For Android sender APK or project documentation, visit: https://github.com/rithulbm/android2pc-stream\n`);
    if (!args.includes('--download-only')) {
      process.exit(1);
    }
  }

  const isDownloadOnly = args.includes('--download-only');
  const isForceDownload = args.includes('--force-download');
  const isSilent = args.includes('-s') || args.includes('--silent');

  // Custom download URL override
  let downloadUrl = PRIMARY_DOWNLOAD_URL;
  const customUrlIdx = args.indexOf('--custom-url');
  if (customUrlIdx !== -1 && args[customUrlIdx + 1]) {
    downloadUrl = args[customUrlIdx + 1];
  }

  const cachedBinary = getCachedBinaryPath();
  let binaryToLaunch = cachedBinary;
  let needsDownload = true;

  // Check if repo-local binary exists (for development / local running)
  const localRepoBinary = path.resolve(__dirname, '..', '..', BINARY_NAME);
  if (!isForceDownload && fs.existsSync(localRepoBinary)) {
    const isLocalValid = await verifyBinaryIntegrity(localRepoBinary, EXPECTED_SHA256);
    if (isLocalValid) {
      binaryToLaunch = localRepoBinary;
      needsDownload = false;
      console.log(`[i] Using verified local repository binary: ${localRepoBinary}`);
    }
  }

  // Check if globally cached binary is already present and valid
  if (needsDownload && !isForceDownload && fs.existsSync(cachedBinary)) {
    process.stdout.write(`[*] Checking cached binary integrity... `);
    const isValid = await verifyBinaryIntegrity(cachedBinary, EXPECTED_SHA256);
    if (isValid) {
      console.log(`OK (SHA-256 verified)`);
      needsDownload = false;
    } else {
      console.log(`MISMATCH (will re-download)`);
      try { fs.unlinkSync(cachedBinary); } catch {}
    }
  }

  // Download if needed
  if (needsDownload) {
    console.log(`\n======================================================`);
    console.log(` Local Camera Receiver Installer (v${VERSION})`);
    console.log(`======================================================`);
    console.log(`[*] Target Cache: ${cachedBinary}`);
    console.log(`[*] Downloading:  ${downloadUrl}\n`);

    try {
      await downloadFile(downloadUrl, cachedBinary);
    } catch (primaryErr) {
      console.warn(`[!] Primary download failed (${primaryErr.message}). Trying fallback mirror...`);
      try {
        await downloadFile(LATEST_DOWNLOAD_URL, cachedBinary);
      } catch (fallbackErr) {
        console.error(`\n[X] Error: Failed to download ${BINARY_NAME}.`);
        console.error(`    Details: ${fallbackErr.message}`);
        console.error(`    You can manually download the installer from: ${LATEST_DOWNLOAD_URL}\n`);
        process.exit(1);
      }
    }

    // Verify SHA-256
    process.stdout.write(`\n[*] Verifying SHA-256 checksum... `);
    const verified = await verifyBinaryIntegrity(cachedBinary, EXPECTED_SHA256);
    if (!verified) {
      const actualHash = await calculateSha256(cachedBinary);
      console.error(`FAILED`);
      console.error(`[X] Security Error: Downloaded binary hash mismatch!`);
      console.error(`    Expected: ${EXPECTED_SHA256}`);
      console.error(`    Actual:   ${actualHash}`);
      try { fs.unlinkSync(cachedBinary); } catch {}
      process.exit(1);
    }
    console.log(`OK (SHA-256 verified)\n`);
  }

  if (isDownloadOnly) {
    console.log(`[✓] Download and verification complete: ${binaryToLaunch}`);
    process.exit(0);
  }

  // Prepare launcher arguments
  const forwardArgs = [];

  if (isSilent) {
    forwardArgs.push('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART');
  }

  // Forward any user-provided passthrough arguments
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--show-qr') {
      forwardArgs.push('/show-qr');
    } else if (arg === '--' && i + 1 < args.length) {
      forwardArgs.push(...args.slice(i + 1));
      break;
    } else if (!arg.startsWith('--download-only') &&
               !arg.startsWith('--force-download') &&
               !arg.startsWith('--clean') &&
               !arg.startsWith('-s') &&
               !arg.startsWith('--silent') &&
               arg !== '--custom-url' &&
               (i === 0 || args[i - 1] !== '--custom-url')) {
      if (arg.startsWith('/')) {
        forwardArgs.push(arg);
      }
    }
  }

  console.log(`[🚀] Launching Local Camera Receiver Setup...`);
  try {
    const exitCode = await launchBinary(binaryToLaunch, forwardArgs);
    process.exit(exitCode);
  } catch (launchErr) {
    console.error(`[X] Launch failed: ${launchErr.message}`);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(`[X] Fatal Error: ${err.message}`);
  process.exit(1);
});
