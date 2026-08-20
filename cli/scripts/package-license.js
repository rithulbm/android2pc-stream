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
} else if (command === 'cleanup') {
  if (fs.existsSync(packageLicense)) {
    fs.unlinkSync(packageLicense);
  }
} else {
  throw new Error('Usage: node scripts/package-license.js <prepare|cleanup>');
}
