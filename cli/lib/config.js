'use strict';

const path = require('path');
const os = require('os');

const VERSION = '0.1.0';
const BINARY_NAME = 'LocalCameraReceiverSetup.exe';
const EXPECTED_SHA256 = '11c4298f3b2a9a396ed78742b493d87b1161cd3670d12952e9f68822b5a3b95d';

const GITHUB_REPO = 'rithulbm/android2pc-stream';
const PRIMARY_DOWNLOAD_URL = `https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/${BINARY_NAME}`;
const LATEST_DOWNLOAD_URL = `https://github.com/${GITHUB_REPO}/releases/latest/download/${BINARY_NAME}`;

/**
 * Returns the directory used for caching the downloaded Windows binary.
 */
function getCacheDirectory() {
  const base = process.env.LOCALAPPDATA ||
    (process.platform === 'win32'
      ? path.join(os.homedir(), 'AppData', 'Local')
      : path.join(os.homedir(), '.local', 'share'));

  return path.join(base, 'local-camera-receiver', 'bin');
}

/**
 * Returns the destination file path for the cached executable.
 */
function getCachedBinaryPath() {
  return path.join(getCacheDirectory(), `${VERSION}-${BINARY_NAME}`);
}

module.exports = {
  VERSION,
  BINARY_NAME,
  EXPECTED_SHA256,
  GITHUB_REPO,
  PRIMARY_DOWNLOAD_URL,
  LATEST_DOWNLOAD_URL,
  getCacheDirectory,
  getCachedBinaryPath,
};
