#define AppName "Local Camera Receiver"
#define AppVersion "0.2.0"
#define PluginId "local-camera-receiver"
#ifndef VcRedistPath
#define VcRedistPath "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Redist\MSVC\v143\vc_redist.x64.exe"
#endif

[Setup]
AppId={{6E7CA4BA-4E2A-4E0B-9346-818D1D6FE92F}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Local Camera Receiver contributors
DefaultDirName={commonappdata}\obs-studio\plugins\{#PluginId}
DisableDirPage=yes
DisableProgramGroupPage=yes
UninstallDisplayName={#AppName} for OBS Studio
UninstallDisplayIcon={app}\bin\64bit\LocalCameraReceiver.exe
SetupIconFile=..\build\generated\app.ico
OutputDir=..\..
OutputBaseFilename=LocalCameraReceiverSetup
Compression=lzma2/ultra64
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
PrivilegesRequired=admin
CloseApplications=yes
CloseApplicationsFilter=obs64.exe,LocalCameraReceiver.exe
RestartApplications=no
SetupLogging=yes
WizardStyle=modern
DisableFinishedPage=no
UsePreviousTasks=yes
LicenseFile=..\..\LICENSE
VersionInfoVersion=0.2.0.0
VersionInfoProductVersion=0.2.0.0
VersionInfoCompany=Local Camera Receiver contributors
VersionInfoDescription=Secure local-network camera input for OBS Studio
VersionInfoProductName={#AppName}
VersionInfoCopyright=GPL-2.0-or-later

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "firewall"; Description: "Allow encrypted camera video on private local networks"; GroupDescription: "Windows Firewall:"; Flags: checkedonce
Name: "startup"; Description: "Keep the pairing helper ready in the system tray after sign-in"; GroupDescription: "Background helper:"; Flags: checkedonce
Name: "desktopicon"; Description: "Create a desktop shortcut for pairing"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "..\build\package\local-camera-receiver\bin\64bit\local-camera-receiver.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion restartreplace uninsrestartdelete
Source: "..\build\package\local-camera-receiver\bin\64bit\LocalCameraReceiver.exe"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion restartreplace uninsrestartdelete
Source: "..\build\package\local-camera-receiver\data\manifest.json"; DestDir: "{app}\data"; Flags: ignoreversion
Source: "..\build\package\local-camera-receiver\data\locale\en-US.ini"; DestDir: "{app}\data\locale"; Flags: ignoreversion
Source: "..\..\LICENSE"; DestDir: "{app}\licenses"; DestName: "GPL-2.0-or-later.txt"; Flags: ignoreversion
Source: "..\docs\third-party-notices.md"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\..\mobile\app\src\main\cpp\third_party\srt\LICENSE"; DestDir: "{app}\licenses"; DestName: "SRT-MPL-2.0.txt"; Flags: ignoreversion
Source: "..\..\mobile\app\src\main\cpp\third_party\botan\license.txt"; DestDir: "{app}\licenses"; DestName: "Botan-BSD-2-Clause.txt"; Flags: ignoreversion
Source: "..\third_party\qrcodegen\Readme.markdown"; DestDir: "{app}\licenses"; DestName: "QR-Code-generator-MIT.md"; Flags: ignoreversion
Source: "{#VcRedistPath}"; DestDir: "{tmp}"; Flags: deleteafterinstall dontcopy

[Icons]
Name: "{autoprograms}\Local Camera Receiver\Pair this PC"; Filename: "{app}\bin\64bit\LocalCameraReceiver.exe"; WorkingDir: "{app}\bin\64bit"
Name: "{autodesktop}\Pair Local Camera Sender"; Filename: "{app}\bin\64bit\LocalCameraReceiver.exe"; WorkingDir: "{app}\bin\64bit"; Tasks: desktopicon
Name: "{commonstartup}\Local Camera Receiver"; Filename: "{app}\bin\64bit\LocalCameraReceiver.exe"; Parameters: "/background"; WorkingDir: "{app}\bin\64bit"; Tasks: startup

[Run]
Filename: "{tmp}\vc_redist.x64.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "Installing the Microsoft Visual C++ runtime..."; Flags: waituntilterminated; BeforeInstall: ExtractTemporaryFile('vc_redist.x64.exe')
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Local Camera Receiver (Private UDP 9000)"""; Flags: runhidden waituntilterminated; Tasks: firewall
Filename: "{sys}\netsh.exe"; Parameters: "{code:FirewallAddParameters}"; StatusMsg: "Allowing Local Camera Receiver on private networks..."; Flags: runhidden waituntilterminated; Tasks: firewall; Check: HasObsExecutable
Filename: "{app}\bin\64bit\LocalCameraReceiver.exe"; Description: "Open receiver setup and keep it available in the system tray"; Flags: runasoriginaluser nowait postinstall skipifsilent
Filename: "{app}\bin\64bit\LocalCameraReceiver.exe"; Parameters: "/background"; Flags: runasoriginaluser nowait runhidden skipifnotsilent; Tasks: startup

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Local Camera Receiver (Private UDP 9000)"""; Flags: runhidden waituntilterminated; RunOnceId: "RemoveLocalCameraReceiverFirewall"

[Code]
var
  ObsExecutable: String;

function CleanExecutablePath(Value: String): String;
var
  CommaPosition: Integer;
begin
  Value := Trim(Value);
  CommaPosition := Pos(',', Value);
  if CommaPosition > 0 then
    Value := Copy(Value, 1, CommaPosition - 1);
  Result := RemoveQuotes(Trim(Value));
end;

function FindObsExecutable(): Boolean;
var
  Candidate: String;
begin
  Result := False;
  Candidate := '';
  if RegQueryStringValue(
       HKLM32,
       'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\OBS Studio',
       'DisplayIcon',
       Candidate) then
    Candidate := CleanExecutablePath(Candidate);

  if (Candidate = '') or not FileExists(Candidate) then
    Candidate := ExpandConstant('{autopf}\obs-studio\bin\64bit\obs64.exe');

  if FileExists(Candidate) then
  begin
    ObsExecutable := Candidate;
    Result := True;
  end;
end;

function HasObsExecutable(): Boolean;
begin
  Result := (ObsExecutable <> '') and FileExists(ObsExecutable);
end;

function FirewallAddParameters(Param: String): String;
begin
  Result :=
    'advfirewall firewall add rule ' +
    'name="Local Camera Receiver (Private UDP 9000)" ' +
    'dir=in action=allow program="' + ObsExecutable + '" ' +
    'enable=yes profile=private protocol=UDP localport=9000 ' +
    'remoteip=localsubnet edge=no';
end;

function InitializeSetup(): Boolean;
var
  ObsVersion: String;
begin
  Result := False;
  if not FindObsExecutable() then
  begin
    MsgBox(
      'OBS Studio 32.2.1 was not found. Install or update OBS Studio first, then run this setup again.',
      mbError,
      MB_OK);
    Exit;
  end;

  if not GetVersionNumbersString(ObsExecutable, ObsVersion) or
     (Pos('32.2.1', ObsVersion) <> 1) then
  begin
    MsgBox(
      'This build is verified for OBS Studio 32.2.1. Update OBS Studio to 32.2.1 before installing this source.',
      mbError,
      MB_OK);
    Exit;
  end;
  Result := True;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpFinished then
  begin
    WizardForm.FinishedHeadingLabel.Caption := 'Local Camera Receiver is ready';
    WizardForm.FinishedLabel.Caption :=
      'The OBS source and background pairing helper were installed. Choose Finish setup to open the receiver.';
    WizardForm.NextButton.Caption := 'Finish setup';
  end;
end;
