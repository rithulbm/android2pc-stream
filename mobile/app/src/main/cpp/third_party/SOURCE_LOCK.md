# Native source lock

The native build is intentionally offline. CMake must fail if either vendored source tree is missing; it must never fetch a mutable branch or release URL.

| Component | Immutable source | Download SHA-256 | Local license |
|---|---|---|---|
| Haivision SRT 1.5.6 | commit `c63c311e88aa55e430e3b7d94b89d790994f88c4` (`v1.5.6`), `https://github.com/Haivision/srt/archive/c63c311e88aa55e430e3b7d94b89d790994f88c4.zip` | `575208e0ce0bdf502d75b9f4f230144a16ad15303d4d7a9f162aea691e8f32bf` | `srt/LICENSE` (MPL-2.0) |
| Botan 3.12.0 | `https://botan.randombit.net/releases/Botan-3.12.0.tar.xz` | `5370f98dc15f8c222ee1ce52cd61c8756a53be0dc57cc4c1b0714d5a09ad74fb` | `botan/license.txt` (BSD-2-Clause) |

## Local changes to SRT's MPL-covered build files

- `CMakeLists.txt` pins Botan 3.12.0, selects only the Botan modules required by SRT's FFI AES-CTR/AES-GCM/PBKDF2/key-wrap implementation, and points at Botan 3.12's generated public-header layout.
- `scripts/FindBotan.cmake` refuses network downloads, requires `SRT_BOTAN_SOURCE_DIR`, supplies Botan 3.12's required absolute prefix, uses its current generated-header layout, and declares valid CMake post-build commands.

No SRT runtime source was modified. The resulting sender enables the SRT 1.5.6 AEAD preview API and requires crypto mode 2 (AES-GCM); mode 1 (AES-CTR) is never accepted as a fallback.
