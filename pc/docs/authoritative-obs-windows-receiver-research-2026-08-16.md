# Authoritative OBS/Windows receiver research

**Evidence date:** 2026-08-16  
**Scope:** a Windows OBS Studio source plugin that receives the phone's SRT caller stream, enforces SRT 1.5.6 AES-256-GCM (`SRTO_CRYPTOMODE=2`) with Botan, demuxes MPEG-TS, and supplies H.264/HEVC video plus AAC audio to OBS.  
**Source policy:** primary upstream sources only: OBS Project documentation/source/release artifacts, Haivision SRT documentation/source, FFmpeg source, and Microsoft documentation. Statements marked **inference** are engineering conclusions drawn from those sources, not upstream guarantees.

## Decision

Build a **native, in-process OBS input source plugin** for the first receiver. Do not treat stock OBS Media Source as satisfying the secure transport contract, and do not insert a standalone virtual-camera application as the initial bridge.

The reason is decisive:

- The current stable OBS release is **32.2.1** (2026-07-24), for Windows 10/11. Its official Windows dependency set uses **SRT 1.5.2 + Mbed TLS**, not SRT 1.5.6 + Botan, and does not enable SRT's AEAD-preview option. Therefore stock OBS cannot implement the required AES-GCM `cryptomode=2` profile. [OBS download/current version](https://obsproject.com/download), [OBS 32.2.1 publish workflow](https://github.com/obsproject/obs-studio/actions/runs/30132506407), [OBS dependency pin](https://github.com/obsproject/obs-studio/blob/32.2.1/CMakePresets.json), [official SRT dependency recipe](https://github.com/obsproject/obs-deps/blob/2026-07-15/deps.ffmpeg/60-srt.ps1)
- FFmpeg 8.1.2's own `libsrt` URL wrapper does not expose `cryptomode` as an AVOption. Consequently, stock Media Source has neither the required SRT build nor a supported FFmpeg option with which to force mode 2. [FFmpeg 8.1.2 `libsrt` wrapper](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/libavformat/libsrt.c)
- Stock OBS does contain the needed **container and decoder path**: its FFmpeg recipe pins FFmpeg 8.1.2, enables `libsrt`, builds shared FFmpeg without disabling the MPEG-TS/H.264/HEVC/AAC decoders, and OBS's Media Source accepts `.ts` and `.aac`. Thus MPEG-TS with AVC/HEVC + AAC is not the blocker; the exact authenticated SRT transport is. [OBS FFmpeg recipe](https://github.com/obsproject/obs-deps/blob/2026-07-15/deps.ffmpeg/99-ffmpeg.ps1), [OBS FFmpeg source](https://github.com/obsproject/obs-studio/blob/32.2.1/plugins/obs-ffmpeg/obs-ffmpeg-source.c), [OBS SRT guide](https://obsproject.com/kb/srt-protocol-streaming-guide)
- OBS's source API already accepts timestamped asynchronous video and audio and performs A/V synchronization. An external virtual camera is an **OBS output** intended to expose an OBS scene to webcam-consuming applications; it is not an input API for feeding OBS, and the virtual camera does not solve synchronized audio ingest. [OBS source API](https://docs.obsproject.com/reference-sources), [OBS Virtual Camera guide](https://obsproject.com/kb/virtual-camera-guide)

A separate receiver process plus a thin OBS source becomes preferable only if crash/security isolation or non-OBS consumers become a product requirement. In that design, use authenticated local IPC/shared memory for both timestamped video and audio. A virtual camera alone is not the bridge.

## 1. Current OBS target and binary compatibility

### Release target

- Pin CI and the initial compatibility matrix to the official **OBS Studio 32.2.1 tag**, not `master`. The OBS download page identifies 32.2.1 as the stable release and Windows 10/11 as supported. [OBS download](https://obsproject.com/download)
- OBS API documentation is generated continuously and may show a newer documentation patch than the published stable binary. For repeatable work, source/API links in this note use the `32.2.1` tag where possible.
- Ship an x64 package first. Windows ARM64 is a separate binary and test target; an x64 DLL cannot load in native ARM64 OBS. OBS itself describes Windows-on-ARM support and third-party plugins there as experimental/architecture-specific. [OBS Windows on Arm FAQ](https://obsproject.com/kb/windows-on-arm)

### Required module ABI surface

An OBS module must export the functions created by `OBS_DECLARE_MODULE()` and a successful `obs_module_load()`. The macro supplies `obs_module_set_pointer`, `obs_module_ver`, and the module pointer; normal modules also provide name/description/author text as appropriate. Register the source from `obs_module_load()` using `obs_register_source()`. [OBS module header](https://github.com/obsproject/obs-studio/blob/32.2.1/libobs/obs-module.h), [module API](https://docs.obsproject.com/reference-modules), [plugin overview](https://docs.obsproject.com/plugins)

The 32.2.1 loader compares the module's libobs API major/minor with the running libobs API and refuses a module built for a newer major/minor; the patch component is ignored in that check. It also validates required exports before initialization. This is a loader rule, **not a blanket promise of ABI compatibility across arbitrary OBS releases**. [32.2.1 module loader](https://github.com/obsproject/obs-studio/blob/32.2.1/libobs/obs-module.c)

Practical policy:

1. Build against the **lowest OBS major/minor that the package claims to support**.
2. Initially claim and test OBS **32.2.x only**; make 32.2.1 the minimum.
3. Test every supported current patch plus the next OBS minor before claiming it. Rebuild/release when public symbols, dependency boundaries, or loader behavior change.
4. Never link to private implementation symbols from `obs-ffmpeg`, `media-playback`, the frontend executable, or bundled FFmpeg. Public `libobs` module/source APIs are the contract.

## 2. Correct OBS source shape and lifetime rules

Register one `obs_source_info` with:

- `type = OBS_SOURCE_TYPE_INPUT`;
- `output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE` (plus only documented flags that are truly needed);
- the normal `get_name`, `create`, `destroy`, `get_defaults`, `get_properties`, and `update` callbacks;
- `activate`/`deactivate` behavior chosen explicitly. A live network receiver should not accidentally keep listening when inactive unless the user setting says it should.

`OBS_SOURCE_DO_NOT_DUPLICATE` is appropriate because copying a live source must not silently allocate a second listener/socket. OBS documents asynchronous video and audio output through `obs_source_output_video()` and `obs_source_output_audio()` and states that it synchronizes audio and video from their timestamps. OBS also requires an asynchronous source to stop submitting frames after its `destroy` callback has returned. [OBS source API and `obs_source_info`](https://docs.obsproject.com/reference-sources)

Safe lifetime design:

- Own SRT receive, demux, decode, and reconnect work on plugin-owned worker threads. Do not block the OBS UI/render thread in property, activate, update, or destroy callbacks.
- Give every receive/decode path a cancellation token; bound all queues by both bytes and duration.
- `destroy`: signal cancellation, close/interrupt the SRT socket, join every worker, drain/release frames, then return. No callback may call `obs_source_output_*` afterward.
- Timestamp decoded frames/audio in one session timebase derived from MPEG-TS PTS/DTS. Do not replace transport timestamps with arrival wall-clock time.
- On reconnect, discard stale compressed/decoded queues and require a fresh PAT/PMT and independently decodable video access unit before resuming output.
- Treat malformed TS, implausible dimensions/rates, excessive PES/section sizes, timestamp jumps, and queue pressure as bounded errors, never unbounded allocation.

OBS's own Media Source illustrates cancellable/joined reconnect threads and forwarding decoded frames through `obs_source_output_video()` / `obs_source_output_audio()`. It is useful as a lifecycle example, but its private `media-playback` interface is not a third-party ABI. [OBS 32.2.1 Media Source implementation](https://github.com/obsproject/obs-studio/blob/32.2.1/plugins/obs-ffmpeg/obs-ffmpeg-source.c)

Use `obs_module_config_path()` for plugin-owned configuration. Never write into the OBS install directory. [OBS module API](https://docs.obsproject.com/reference-modules)

## 3. Official plugin template and Windows build baseline

The official [OBS plugin template](https://github.com/obsproject/obs-plugintemplate) is the correct scaffold, but its dependency pins must be audited rather than copied blindly.

Verified template baseline as of this evidence date:

- `CMakeLists.txt` supports CMake **3.28 through 3.30**, creates a `MODULE` library, finds `libobs`, and links `OBS::libobs`. Frontend API and Qt are optional and off by default; this input source does not need either for v1. [template `CMakeLists.txt`](https://github.com/obsproject/obs-plugintemplate/blob/master/CMakeLists.txt)
- The Windows preset uses **Visual Studio 17 2022**, x64, Windows SDK **10.0.22621.0**, and `RelWithDebInfo`; the README recommends CMake **3.30.5**. [template presets](https://github.com/obsproject/obs-plugintemplate/blob/master/CMakePresets.json), [template README](https://github.com/obsproject/obs-plugintemplate/blob/master/README.md)
- The template compiler policy checks for at least Windows SDK 10.0.20348 and enables reproducibility/strictness switches such as `/Brepro`, `/utf-8`, and `/permissive-`. [template Windows compiler configuration](https://github.com/obsproject/obs-plugintemplate/blob/master/cmake/windows/compilerconfig.cmake)
- The official PowerShell flow configures, builds, installs, and packages through CMake presets. Its package script expects PowerShell 7.2 in CI. [Windows build script](https://github.com/obsproject/obs-plugintemplate/blob/master/.github/scripts/Build-Windows.ps1), [Windows package script](https://github.com/obsproject/obs-plugintemplate/blob/master/.github/scripts/Package-Windows.ps1)

Important drift: template `master` currently pins an older OBS/dependencies snapshot in `buildspec.json`. Fork the template, then pin OBS **32.2.1 source by immutable tag/commit and hash** plus a matching official dependency bundle. Do not float `master` in release builds. [template `buildspec.json`](https://github.com/obsproject/obs-plugintemplate/blob/master/buildspec.json)

OBS 32.2.1's own full-application Windows preset uses **Visual Studio 18 2026** and Windows SDK **10.0.26100.0**, whereas the official standalone plugin template still uses VS 2022/SDK 22621. For the production 32.2.1 package, prefer the exact OBS compiler/SDK tuple and update the template scripts accordingly. Keep a VS 2022 build only as a separately tested compatibility artifact if broader developer-tool availability warrants it. In either case, link only the C `libobs` API and match the dynamic CRT model. [OBS 32.2.1 presets](https://github.com/obsproject/obs-studio/blob/32.2.1/CMakePresets.json), [plugin-template presets](https://github.com/obsproject/obs-plugintemplate/blob/master/CMakePresets.json)

Recommended reproducible build tuple for this plugin:

| Item | Pin / rule |
|---|---|
| OBS SDK/source | OBS Studio 32.2.1 tag and verified commit/hash |
| Generator | Visual Studio 18 2026 x64 for the production OBS 32.2.1 package; retain VS 2022 only as a separately tested template-compatible build |
| CMake | 3.30.5 (inside the template's 3.28–3.30 range) |
| Windows SDK | 10.0.26100.0 to match OBS 32.2.1; build/test on supported Windows 10 and 11 |
| Configuration | Release for shipping; RelWithDebInfo + private symbols for diagnostics |
| Runtime | MSVC dynamic runtime (`/MD`), consistent with the official OBS dependency recipe |
| SRT | 1.5.6, immutable source hash, `ENABLE_AEAD_API_PREVIEW=ON`, Botan encryption backend |
| Architectures | x64 initially; separately compiled/signed/tested ARM64 later |

## 4. Exact stock-OBS protocol compatibility

### What stock OBS 32.2.1 ships

OBS 32.2.1 selects the official `obs-deps` bundle dated **2026-07-15**. In that tagged bundle:

- `60-srt.ps1` pins **SRT 1.5.2**, builds shared and static forms, turns apps off, and sets `USE_ENCLIB=mbedtls`. It does **not** set `ENABLE_AEAD_API_PREVIEW=ON`. [SRT recipe](https://github.com/obsproject/obs-deps/blob/2026-07-15/deps.ffmpeg/60-srt.ps1)
- `60-mbedtls.ps1` pins the bundled Mbed TLS build. [Mbed TLS recipe](https://github.com/obsproject/obs-deps/blob/2026-07-15/deps.ffmpeg/60-mbedtls.ps1)
- `99-ffmpeg.ps1` pins **FFmpeg 8.1.2** at an exact commit, enables `libsrt`, H.264 encoding support, and the normal shared FFmpeg build. It does not globally disable demuxers/decoders, so its native MPEG-TS, AVC, HEVC, and AAC paths remain built. [FFmpeg recipe](https://github.com/obsproject/obs-deps/blob/2026-07-15/deps.ffmpeg/99-ffmpeg.ps1)
- OBS's `obs-ffmpeg` module links the FFmpeg and Libsrt packages; Media Source lists `.ts` and `.aac`, accepts a network URL and input format, and outputs decoded audio/video to libobs. [OBS FFmpeg module build](https://github.com/obsproject/obs-studio/blob/32.2.1/plugins/obs-ffmpeg/CMakeLists.txt), [Media Source source](https://github.com/obsproject/obs-studio/blob/32.2.1/plugins/obs-ffmpeg/obs-ffmpeg-source.c)

The official OBS SRT guide confirms the ordinary listener recipe (`srt://0.0.0.0:port?mode=listener`, Media Source, `mpegts`) and recommends latency based on RTT. That guide establishes normal SRT/MPEG-TS support, not this AES-GCM build. [OBS SRT guide](https://obsproject.com/kb/srt-protocol-streaming-guide)

### Why the required profile does not work in stock OBS

SRT documents `SRTO_CRYPTOMODE=2` as AES-GCM/AEAD, with TSBPD required. SRT's build documentation says AEAD is a preview feature gated by `ENABLE_AEAD_API_PREVIEW=ON` and supported through OpenSSL-EVP or Botan, with the official/non-preview API planned for 1.6.0. Mbed TLS is not listed as an AEAD provider for this SRT feature. [SRT socket options](https://github.com/Haivision/srt/blob/master/docs/API/API-socket-options.md), [SRT build options](https://github.com/Haivision/srt/blob/master/docs/build/build-options.md)

Additionally, the exact FFmpeg 8.1.2 `libsrt` wrapper used by the OBS dependency recipe exposes passphrase, key length, latency and other SRT options but no `cryptomode` AVOption. An OBS Media Source URL/FFmpeg-options field therefore cannot be used as a supported escape hatch to force GCM. [FFmpeg 8.1.2 `libsrt.c`](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/libavformat/libsrt.c)

The required sender contract also pins SRT **1.5.6**, whose release fixes encryption downgrade/state-machine and KMREQ/KMRSP security defects. [SRT 1.5.6 release](https://github.com/Haivision/srt/releases/tag/v1.5.6)

Therefore:

| Capability | Stock OBS 32.2.1 |
|---|---|
| SRT listener / MPEG-TS input | Yes |
| MPEG-TS H.264 + AAC | Yes; normal FFmpeg/OBS path |
| MPEG-TS HEVC + AAC | Decoder/container path is present; hardware/performance still needs device testing |
| SRT AES-CTR compatibility | Likely normal SRT interoperability, but CTR lacks payload authentication and is outside this goal |
| SRT 1.5.6 | **No**; official dependency is 1.5.2 |
| Botan-backed SRT | **No**; official dependency uses Mbed TLS |
| `SRTO_CRYPTOMODE=2` AES-GCM | **No**; required AEAD preview option/provider is absent |

Do not add a “compatibility fallback” that silently negotiates AES-CTR. The source must fail closed when GCM is unavailable or negotiation does not end in `cryptomode=2` and a secured key-management state.

### Private native dependency isolation

**Inference:** because the plugin is loaded into the same Windows process that already loads OBS's SRT/FFmpeg DLLs, shipping another generic `srt.dll` or generic `avcodec-*.dll` beside the plugin creates a material DLL-resolution and ABI-collision risk. Windows documents that a DLL with the same module name already loaded can satisfy a later resolution before ordinary directory search. Do not overwrite or reuse OBS's bundled binaries. [Microsoft DLL search order](https://learn.microsoft.com/en-us/windows/win32/dlls/dynamic-link-library-search-order)

Preferred v1 packaging:

1. Statically link a private **SRT 1.5.6 + Botan AEAD** build into the plugin and ensure its symbols are not exported from the OBS module.
2. Use a pinned, minimal demux/decode implementation with the same isolation discipline. If FFmpeg is used, either statically link the reviewed subset or ship uniquely named/private DLLs with a controlled load path; never assume OBS's private FFmpeg ABI is a plugin contract.
3. Produce an SBOM, exact source/build recipe, license notices, and corresponding-source offer required by all distributed native components.
4. Run a process-module audit on the installed build to prove the receiver is using the intended SRT 1.5.6/Botan image, not OBS's 1.5.2 SRT DLL.

This must be resolved experimentally in the build spike because static FFmpeg/SRT/Botan linkage, compiler flags, and licensing obligations can constrain the final isolation choice.

## 5. Installation, discovery, and plugin manager behavior

### Canonical Windows layout

OBS's official plugin guide recommends the per-machine path:

```text
C:\ProgramData\obs-studio\plugins\<plugin-name>\
  bin\64bit\<plugin-name>.dll
  data\...
```

The DLL filename must match its plugin folder. The template's Windows helpers install the binary below `<plugin>/bin/64bit` and data below `<plugin>/data`; its default prefix is `%ALLUSERSPROFILE%/obs-studio/plugins`, which normally resolves to `C:\ProgramData\obs-studio\plugins`. [OBS plugin guide](https://obsproject.com/kb/plugins-guide), [template Windows install helpers](https://github.com/obsproject/obs-plugintemplate/blob/master/cmake/windows/helpers.cmake), [template defaults](https://github.com/obsproject/obs-plugintemplate/blob/master/cmake/windows/defaults.cmake)

The legacy `C:\Program Files\obs-studio\obs-plugins\64bit` location still works today but OBS says support will be removed in the future. Do not install there. Portable/custom OBS installations require the user to select the matching portable plugin/data tree. OBS also documents `OBS_PLUGINS_PATH` and `OBS_PLUGINS_DATA_PATH` overrides. [OBS plugin guide](https://obsproject.com/kb/plugins-guide)

No COM registration or OBS-specific Windows Registry registration is required for a normal source plugin. Discovery is file-path based and OBS must restart after install/update/enable changes.

### OBS 32 plugin manager limits

OBS 32's basic Plugin Manager enumerates modules that OBS discovers/loads and persists enable/disable state. Its Discovery and Updates pages are explicitly “Coming Soon”; it is not presently an installer/updater for arbitrary third-party binaries. [Plugin Manager implementation](https://github.com/obsproject/obs-studio/tree/32.2.1/frontend/plugin-manager), [Plugin Manager window](https://github.com/obsproject/obs-studio/blob/32.2.1/frontend/plugin-manager/PluginManagerWindow.cpp)

Provide a signed external installer and a correctly structured zip. An optional `data/manifest.json` can supply display name, ID, version, OS/architecture, description and support/repository URLs for richer module metadata; current loader source parses those fields. Absence of a manifest does not replace the required exported module functions. [OBS module loader/manifest parser](https://github.com/obsproject/obs-studio/blob/32.2.1/libobs/obs-module.c)

OBS Safe Mode and `--only-bundled-plugins` intentionally skip third-party modules that are not on the frontend's safe-module allowlist. The plugin must report this clearly in troubleshooting and must never try to bypass or impersonate the safe list. [OBS module-loader safe-module path](https://github.com/obsproject/obs-studio/blob/32.2.1/libobs/obs-module.c)

### Installer requirements

- Require OBS to be closed before replacing binaries; stage then atomically replace the plugin's own folder.
- Never overwrite OBS core/bundled DLLs or another plugin's files.
- Per-machine ProgramData install normally needs elevation. Portable/custom install needs an explicit validated OBS root selected by the user.
- If offering a Windows Firewall rule, ask explicitly and scope it to **inbound UDP**, the selected receiver port, the OBS executable, **Private** profile, and preferably `LocalSubnet`. Remove only that owned rule on uninstall. Microsoft documents program, address, port, protocol and profile filters on `New-NetFirewallRule`. [Microsoft `New-NetFirewallRule`](https://learn.microsoft.com/en-us/powershell/module/netsecurity/new-netfirewallrule)
- Bind to the chosen LAN address/private interface by default, not every interface (`0.0.0.0`). A broad bind may be an explicit advanced choice with a warning.
- Uninstall only the plugin-owned directory, its owned firewall rule, and normal installer registration. Preserve user configuration/secrets unless the user explicitly selects “remove settings”.
- Publish SHA-256 hashes and an SBOM alongside each release.

## 6. Windows runtime, signing, and SmartScreen

### MSVC runtime

Use the official template's MSVC dynamic-runtime model. OBS's Windows dependency recipe builds FFmpeg with `-MD`, and OBS 32.2.1 checks `msvcp140.dll` at startup and refuses a runtime whose v14 minor is below 40. Microsoft states that the latest supported Visual C++ v14 Redistributable is binary-compatible across supported Visual Studio 2017-and-later toolsets, while the installed runtime must be at least as new as the build tools used. [OBS FFmpeg build flags](https://github.com/obsproject/obs-deps/blob/2026-07-15/deps.ffmpeg/99-ffmpeg.ps1), [OBS runtime check](https://github.com/obsproject/obs-studio/blob/32.2.1/frontend/obs-main.cpp), [Microsoft latest supported VC++ Redistributable](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)

Do not copy loose CRT DLLs into the plugin folder. The installer may detect and chain Microsoft's current x64 VC++ Redistributable if required. Avoid changing to `/MT` merely to remove the prerequisite: crossing a libobs ABI boundary with mismatched CRT ownership/allocators is an unnecessary risk.

### Authenticode and SmartScreen

Signing is not an OBS module-loader requirement; the current loader does not enforce Authenticode. It is nevertheless required for a credible public Windows distribution.

- Sign every shipped PE binary (plugin DLL and any private helper DLL/EXE), then sign the MSI/EXE installer. Use one stable legal publisher identity.
- Microsoft's SignTool documentation requires specifying the file digest algorithm; use SHA-256 (`/fd SHA256`) and an RFC 3161 timestamp with SHA-256 (`/tr ... /td SHA256`), then verify signatures in CI. [Microsoft SignTool](https://learn.microsoft.com/en-us/windows/win32/seccrypto/signtool)
- Microsoft recommends Artifact Signing for eligible non-Store applications and otherwise documents organization-validation code-signing certificates. [Microsoft code-signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)
- SmartScreen reputation considers both publisher reputation and the specific downloaded file. A valid signature—EV included—does **not** guarantee that a new build will avoid a warning; self-signed certificates have no public reputation benefit. Sign consistently, avoid changing publisher identity, timestamp every release, and provide hash-verification instructions. [Microsoft SmartScreen reputation](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation)

The plugin manager may still disable a signed plugin, Safe Mode may skip it, antivirus may quarantine it, and Windows may warn about its installer. Signing proves publisher/file integrity under the certificate trust model; it does not prove safety or compatibility.

## 7. Secrets and local security boundary

Do not store the SRT passphrase or a full credential-bearing URL in ordinary OBS source settings. OBS scene collections are serialized configuration, may be exported/copied, and OBS's own Media Source can log its input/options. The official Media Source implementation demonstrates why credential-bearing text fields are inappropriate for secrets. [OBS Media Source implementation](https://github.com/obsproject/obs-studio/blob/32.2.1/plugins/obs-ffmpeg/obs-ffmpeg-source.c)

Recommended Windows contract:

- Put only a random credential reference/slot ID in OBS source settings.
- Store the passphrase in a plugin-private file below the module configuration directory, encrypted with Windows DPAPI under the **current user** using `CryptProtectData`; authenticate/decrypt with `CryptUnprotectData` and fail closed on error. Do not use `CRYPTPROTECT_LOCAL_MACHINE`, which allows any user on the machine to decrypt. [Microsoft `CryptProtectData`](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata), [Microsoft `CryptUnprotectData`](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptunprotectdata)
- Never log the passphrase, QR payload, credential URL, encryption key material, raw packets, or decrypted media payload.
- Redact OBS logs and crash annotations; include only a random session ID, state transition, numeric SRT error/reject code, negotiated non-secret algorithm, codec, dimensions/rates, and bounded counters.
- Treat possession of the pairing passphrase as the receiver identity boundary; SRT PSK is not a certificate identity system.

## 8. Native plugin versus standalone receiver

| Option | Security/engineering result | Decision |
|---|---|---|
| Stock OBS Media Source | Simple and supports ordinary SRT/MPEG-TS/codecs, but official OBS 32.2.1 lacks SRT 1.5.6 + Botan AEAD and cannot meet fail-closed GCM | Reject for required profile; keep only as an AES-CTR interoperability diagnostic if explicitly allowed in a non-production lab build |
| Native OBS input source | Direct public API for timestamped video+audio; lowest latency and least user plumbing; parser/decoder shares OBS process | **Build first**, with strict dependency isolation, bounded parsing, fuzzing, and crash-hardening |
| Standalone app + virtual camera | Extra driver/registration/signing surface; virtual camera is a video-oriented output mechanism, not a synchronized OBS ingest contract; audio needs another device/path | Reject as initial architecture |
| Standalone receiver + thin OBS IPC source | Best process isolation and reuse outside OBS, but adds authenticated IPC, shared-memory lifecycle, versioning, and still requires an OBS source for proper A/V | Future isolation option if threat model/performance testing justifies it |
| Full custom OBS build | Can replace stock SRT/FFmpeg, but creates a full OBS fork/update/security/distribution burden | Reject unless upstream OBS itself must be redistributed as a managed appliance |

The native plugin recommendation is conditional on a hostile-input hardening gate. SRT and MPEG-TS are network-facing native parsers inside OBS. Before public distribution, fuzz the TS/PES/codec-boundary adapters, impose strict resource limits, run ASan/UBSan fuzz builds outside the shipping MSVC build, and exercise cancellation/reconnect/destruction races. If those gates cannot be met, move parsing/decoding out of process and retain only a narrow, authenticated local IPC source in OBS.

## 9. Required build and release verification

Do not call the receiver complete until all of these are evidenced on clean Windows 10 and Windows 11 VMs:

1. **ABI/discovery:** clean OBS 32.2.1 x64 loads the installed module from ProgramData; source appears; enable/disable and Safe Mode behavior are correct; no missing imports.
2. **Exact crypto:** loaded-module inventory proves the plugin uses SRT 1.5.6 + Botan; connection succeeds only with `cryptomode=2`, AES-256 key length, enforced encryption, and secured key-management state. Wrong passphrase, CTR-only peer, missing encryption, and downgrade attempts all fail closed.
3. **Media:** long-run H.264/AAC and HEVC/AAC MPEG-TS tests verify PAT/PMT changes, keyframe start, PTS/DTS/PCR handling, audio sample-rate changes, resolution/orientation changes, and discontinuities.
4. **Lifecycle:** repeated source create/update/duplicate/delete, scene collection switch, profile switch, OBS shutdown, network loss, phone app kill/restart, sleep/wake, interface change, and 1,000 reconnect cycles produce no deadlock, use-after-free, stale frame, or unbounded queue.
5. **Dependency isolation:** process module list and automated assertions show no accidental binding to OBS's SRT 1.5.2/Mbed TLS or private FFmpeg ABI.
6. **Packaging:** fresh install, upgrade, downgrade rejection, uninstall, custom/portable location, missing VC runtime, non-admin path, firewall consent/removal, and OBS-running handling are tested.
7. **Trust:** every PE and installer signature verifies offline/online as applicable, timestamps validate after signing-certificate expiry, published SHA-256 hashes match, and SmartScreen behavior is recorded without claiming a guaranteed warning-free result.
8. **Malformed input:** fuzz corpus and adversarial TS/SRT tests show bounded CPU/RAM/disk/log output and deterministic teardown.

## 10. Explicit uncertainties and follow-ups

- **SRT AEAD remains preview in 1.5.6.** The strongest required mode is available only through a preview build flag, and upstream says the official API is planned for 1.6.0. Pin 1.5.6 now because it contains critical security fixes, but re-audit when 1.6.0 releases; do not silently change the wire contract. [SRT build options](https://github.com/Haivision/srt/blob/master/docs/build/build-options.md), [SRT 1.5.6 release](https://github.com/Haivision/srt/releases/tag/v1.5.6)
- **No official evidence establishes stock OBS GCM interoperability** because the published build recipe positively establishes the opposite dependency/provider configuration. A stock Media Source must not be presented as secure-protocol compatible.
- **HEVC performance is machine-dependent.** Stock FFmpeg contains an HEVC decoder path, but usable resolution/frame-rate/latency depends on CPU/GPU, driver, bit depth, chroma format, and whether the eventual plugin safely integrates hardware decoding. Make H.264/AAC the broad fallback; gate HEVC by a receiver capability exchange and live test.
- **OBS ABI support is a product claim, not inferred from one version check.** The loader's major/minor rule does not substitute for regression testing each supported OBS release.
- **Static/private dependency packaging needs a license and symbol audit.** The exact Botan/SRT/FFmpeg configuration, transitive libraries, symbol visibility, and source-notice obligations must be reviewed before choosing static versus uniquely named private DLLs.
- **SmartScreen reputation is external state.** Signing and timestamping improve identity/integrity but cannot guarantee no prompt for a new or low-reputation download.
- **Plugin Manager discovery/update is not available today.** Re-check its official implementation on every OBS major/minor; do not design an updater around “Coming Soon” UI.

## Primary-source index

- [OBS current download/release](https://obsproject.com/download)
- [OBS 32.2.1 source tree](https://github.com/obsproject/obs-studio/tree/32.2.1)
- [OBS plugin template](https://github.com/obsproject/obs-plugintemplate)
- [OBS plugin guide](https://obsproject.com/kb/plugins-guide)
- [OBS plugin/module/source API](https://docs.obsproject.com/plugins)
- [OBS module API](https://docs.obsproject.com/reference-modules)
- [OBS source API](https://docs.obsproject.com/reference-sources)
- [OBS SRT guide](https://obsproject.com/kb/srt-protocol-streaming-guide)
- [OBS dependency recipes, 2026-07-15](https://github.com/obsproject/obs-deps/tree/2026-07-15/deps.ffmpeg)
- [SRT 1.5.6 release](https://github.com/Haivision/srt/releases/tag/v1.5.6)
- [SRT socket options](https://github.com/Haivision/srt/blob/master/docs/API/API-socket-options.md)
- [SRT build options](https://github.com/Haivision/srt/blob/master/docs/build/build-options.md)
- [FFmpeg MPEG-TS demuxer](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mpegts.c)
- [Microsoft latest supported VC++ Redistributable](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)
- [Microsoft code-signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)
- [Microsoft SmartScreen reputation](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation)
- [Microsoft SignTool](https://learn.microsoft.com/en-us/windows/win32/seccrypto/signtool)
