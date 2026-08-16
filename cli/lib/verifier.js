'use strict';

const fs = require('fs');
const crypto = require('crypto');

/**
 * Calculates SHA-256 hash of a file stream.
 * @param {string} filePath
 * @returns {Promise<string>} Hex-encoded lowercase SHA-256 hash
 */
function calculateSha256(filePath) {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(filePath)) {
      return reject(new Error(`File does not exist: ${filePath}`));
    }

    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);

    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('error', (err) => reject(err));
    stream.on('end', () => resolve(hash.digest('hex').toLowerCase()));
  });
}

/**
 * Verifies if the file matches the expected SHA-256 hash.
 * @param {string} filePath
 * @param {string} expectedHash
 * @returns {Promise<boolean>}
 */
async function verifyBinaryIntegrity(filePath, expectedHash) {
  try {
    if (!fs.existsSync(filePath)) return false;
    const actual = await calculateSha256(filePath);
    return actual === expectedHash.toLowerCase();
  } catch {
    return false;
  }
}

module.exports = {
  calculateSha256,
  verifyBinaryIntegrity,
};
