'use strict';

const { spawn } = require('child_process');
const fs = require('fs');

/**
 * Launches the Windows installer or helper binary.
 * @param {string} executablePath
 * @param {string[]} args
 * @param {object} [options]
 * @returns {Promise<number>} Exit code of the launched process
 */
function launchBinary(executablePath, args = [], options = {}) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(executablePath)) {
      return reject(new Error(`Executable not found at: ${executablePath}`));
    }

    if (process.platform !== 'win32') {
      return reject(
        new Error(
          `Local Camera Receiver is currently designed for 64-bit Windows (OBS Studio on Windows 10/11). Detected OS: ${process.platform}`
        )
      );
    }

    const isDetached = options.detached || false;

    const child = spawn(executablePath, args, {
      stdio: isDetached ? 'ignore' : 'inherit',
      detached: isDetached,
      windowsHide: false,
    });

    if (isDetached) {
      child.unref();
      return resolve(0);
    }

    child.on('error', (err) => {
      reject(err);
    });

    child.on('exit', (code, signal) => {
      if (signal) {
        return reject(new Error(`Process terminated with signal: ${signal}`));
      }
      resolve(code || 0);
    });
  });
}

module.exports = {
  launchBinary,
};
