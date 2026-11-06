#define AppName "ConectaTudo 1.6"
#define AppVersion "1.6"
#define AppExe "ConectaTudo-Windows-1.6.exe"
[Setup]
AppId={{F7C5A1D3-02A6-4BA2-BD91-E21E909AB516}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Projeto ConectaTudo
DefaultDirName={localappdata}\Programs\ConectaTudo-1.6
DefaultGroupName=ConectaTudo 1.6
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=ConectaTudo-Setup-1.6
Compression=lzma
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#AppExe}
[Files]
Source: "..\dist\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
[Icons]
Name: "{autoprograms}\ConectaTudo 1.6"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\ConectaTudo 1.6"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
[Tasks]
Name: desktopicon; Description: "Criar atalho na área de trabalho"; Flags: unchecked
[Run]
Filename: "{app}\{#AppExe}"; Description: "Abrir ConectaTudo"; Flags: nowait postinstall skipifsilent
