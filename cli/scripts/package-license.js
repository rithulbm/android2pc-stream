'use strict';

const fs = require('fs');
const path = require('path');

const cliRoot = path.resolve(__dirname, '..');
const repositoryLicense = path.resolve(cliRoot, '..', 'LICENSE');
const packageLicense = path.join(cliRoot, 'LICENSE');
const command = process.argv[2];

if (command === 'prepare') {
  if (!fs.existsSync(repositoryLicense)) {
    throw new Error(`Repository license is missing: ${repositoryLicense}`);
  }
  fs.copyFileSync(repositoryLicense, packageLicense);
  process.stdout.write('Prepared canonical LICENSE for npm package.\n');
} else if (command === 'cleanup') {
  if (fs.existsSync(packageLicense)) {
    fs.unlinkSync(packageLicense);
  }
  process.stdout.write('Cleaned temporary npm LICENSE copy.\n');
} else {
  throw new Error('Usage: node scripts/package-license.js <prepare|cleanup>');
}
