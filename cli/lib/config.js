'use strict';

const path = require('path');
const os = require('os');
const { version: VERSION } = require('../package.json');

const BINARY_NAME = 'LocalCameraReceiverSetup.exe';
const EXPECTED_SHA256 = 'c1cd3d32a009e73c3a0741b8e7c5395d3be7968341cee9297fe1b13985c81957';

const GITHUB_REPO = 'rithulbm/android2pc-stream';
// An npm package must download an immutable installer matching its pinned hash.
// Tracking mutable main would make older CLI releases fail integrity checks as
// soon as a newer root installer is published.
const PUBLISHED_BINARY_COMMIT = '93868b483f3605a4dd8a7853d8ae88da1fcbe0da';
const PRIMARY_DOWNLOAD_URL = `https://raw.githubusercontent.com/${GITHUB_REPO}/${PUBLISHED_BINARY_COMMIT}/${BINARY_NAME}`;
const LATEST_DOWNLOAD_URL = `https://github.com/${GITHUB_REPO}/raw/${PUBLISHED_BINARY_COMMIT}/${BINARY_NAME}`;

/** Returns the directory used for caching the downloaded Windows binary. */
function getCacheDirectory() {
  const base = process.env.LOCALAPPDATA ||
    (process.platform === 'win32'
      ? path.join(os.homedir(), 'AppData', 'Local')
      : path.join(os.homedir(), '.local', 'share'));

  return path.join(base, 'local-camera-receiver', 'bin');
}

/** Returns the destination file path for the cached executable. */
function getCachedBinaryPath() {
  return path.join(getCacheDirectory(), `${VERSION}-${BINARY_NAME}`);
}

module.exports = {
  VERSION,
  BINARY_NAME,
  EXPECTED_SHA256,
  GITHUB_REPO,
  PUBLISHED_BINARY_COMMIT,
  PRIMARY_DOWNLOAD_URL,
  LATEST_DOWNLOAD_URL,
  getCacheDirectory,
  getCachedBinaryPath,
};
