'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');
const { URL } = require('url');
const { version: PACKAGE_VERSION } = require('../package.json');

/**
 * Format bytes into human readable string (e.g. 12.4 MB)
 */
function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

/**
 * Render terminal progress bar
 */
function renderProgressBar(received, total, startTime) {
  const width = 30;
  const elapsedSeconds = Math.max(0.1, (Date.now() - startTime) / 1000);
  const speed = received / elapsedSeconds;
  const percent = total > 0 ? Math.min(100, Math.floor((received / total) * 100)) : 0;
  const filled = total > 0 ? Math.round((width * percent) / 100) : 0;
  const empty = width - filled;

  const bar = '='.repeat(Math.max(0, filled - 1)) + (filled > 0 ? '>' : '') + ' '.repeat(Math.max(0, empty));
  const eta = total > 0 && speed > 0 ? `${Math.ceil((total - received) / speed)}s` : '--';

  const line = `  [${bar}] ${percent}% | ${formatBytes(received)} / ${total > 0 ? formatBytes(total) : '???'} | ${formatBytes(speed)}/s | ETA: ${eta}`;

  if (process.stdout.isTTY) {
    process.stdout.write(`\r${line}`);
  }
}

/**
 * Downloads a file over HTTP/HTTPS with redirect following and progress reporting.
 * @param {string} url
 * @param {string} destinationPath
 * @param {object} [options]
 * @returns {Promise<string>}
 */
function downloadFile(url, destinationPath, options = {}) {
  const maxRedirects = options.maxRedirects ?? 5;

  return new Promise((resolve, reject) => {
    const parsedUrl = new URL(url);
    const protocol = parsedUrl.protocol === 'https:' ? https : http;

    const requestOptions = {
      headers: {
        'User-Agent': `local-camera-receiver-npm-installer/${PACKAGE_VERSION}`,
        'Accept': 'application/octet-stream, */*',
      },
    };

    const req = protocol.get(url, requestOptions, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        if (maxRedirects <= 0) {
          res.resume();
          return reject(new Error(`Too many redirects while downloading ${url}`));
        }

        const redirectUrl = new URL(res.headers.location, url).toString();
        res.resume();
        return downloadFile(redirectUrl, destinationPath, { ...options, maxRedirects: maxRedirects - 1 })
          .then(resolve)
          .catch(reject);
      }

      if (res.statusCode !== 200) {
        res.resume();
        return reject(new Error(`Download failed with HTTP status ${res.statusCode}: ${res.statusMessage}`));
      }

      const totalBytes = parseInt(res.headers['content-length'] || '0', 10);
      const tempPath = `${destinationPath}.tmp.${Date.now()}`;

      fs.mkdirSync(path.dirname(destinationPath), { recursive: true });
      const fileStream = fs.createWriteStream(tempPath);

      let receivedBytes = 0;
      const startTime = Date.now();

      res.on('data', (chunk) => {
        receivedBytes += chunk.length;
        renderProgressBar(receivedBytes, totalBytes, startTime);
      });

      res.pipe(fileStream);

      fileStream.on('finish', () => {
        fileStream.close(() => {
          if (process.stdout.isTTY) {
            process.stdout.write('\n');
          }

          try {
            if (fs.existsSync(destinationPath)) {
              fs.unlinkSync(destinationPath);
            }
            fs.renameSync(tempPath, destinationPath);
            resolve(destinationPath);
          } catch (renameErr) {
            try { if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath); } catch {}
            reject(renameErr);
          }
        });
      });

      fileStream.on('error', (err) => {
        try { if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath); } catch {}
        reject(err);
      });
    });

    req.on('error', reject);
    req.setTimeout(30000, () => {
      req.destroy(new Error('Download connection timed out after 30 seconds'));
    });
  });
}

module.exports = {
  downloadFile,
  formatBytes,
};
