const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { buildApplyUpdateScript } = require("../../src/update-apply.js");

const powershell = process.env.RIVER_TEST_POWERSHELL || (process.platform === "win32" ? "powershell.exe" : "pwsh");
const shellEnv = { ...process.env };
// A pwsh-based CI step can export module paths for PowerShell 7. Let Windows
// PowerShell 5.1 initialize its own standard modules, as it does from Explorer.
if (process.platform === "win32" && /(^|[\\/])powershell(?:\.exe)?$/i.test(powershell)) {
  for (const key of Object.keys(shellEnv)) if (key.toLowerCase() === "psmodulepath") delete shellEnv[key];
}
const hasPowerShell = !spawnSync(powershell, ["-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"], { windowsHide: true, env: shellEnv }).error;
const quote = (value) => String(value || "").replace(/'/g, "''");
const ps = (value) => `'${quote(value)}'`;

function write(root, relative, contents) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, contents);
  return file;
}

function snapshot(root) {
  return Object.fromEntries(fs.readdirSync(root, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile()).map((entry) => {
      const file = path.join(entry.parentPath || entry.path, entry.name);
      return [path.relative(root, file).split(path.sep).join("/"), fs.readFileSync(file).toString("base64")];
    }).sort(([a], [b]) => a.localeCompare(b)));
}

function apply(root, { sourceDir, installDir, mode = "delta", manifestPath, faults = {}, buildScript = buildApplyUpdateScript }) {
  const updateRoot = path.join(root, "updates");
  const logPath = path.join(updateRoot, "apply.log");
  const launches = path.join(root, "launches.txt");
  const trace = path.join(root, "filesystem-trace.txt");
  fs.rmSync(launches, { force: true });
  fs.rmSync(trace, { force: true });
  const script = buildScript({ pid: 0, sourceDir, installDir, relaunchExe: path.join(installDir, "RiverClient.exe"),
    relaunchFallbackExe: path.join(installDir, "RiverClient.exe"), logPath, updateRoot, mode, manifestPath });
  // Run the actual generated script with real filesystem cmdlets. Process control and
  // retry delays are suppressed; failures are injected at the filesystem boundary.
  const wrapper = String.raw`
function Matches-Suffix([string]$file, [string]$suffix) {
  return $suffix -ne '' -and $file.Replace('\', '/').EndsWith($suffix, [StringComparison]::OrdinalIgnoreCase)
}
function Start-Sleep { param($Seconds, $Milliseconds) }
function Wait-Process { param($Id, $ErrorAction) }
function Get-Process { param($Name, $ErrorAction) }
function Start-Process { param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle)
  Add-Content -LiteralPath ${ps(launches)} -Value ($FilePath + ' ' + ($ArgumentList -join ' '))
}
function Copy-Item {
  [CmdletBinding()] param([string]$LiteralPath, [string]$Destination, [switch]$Force)
  Add-Content -LiteralPath ${ps(trace)} -Value ("Copy: $LiteralPath -> $Destination")
  if ((Matches-Suffix $LiteralPath ${ps(faults.backup && '/' + faults.backup)}) -and $Destination.Contains('transactions')) { throw 'Injected backup failure' }
  if (Matches-Suffix $LiteralPath ${ps(faults.apply && '/' + path.basename(sourceDir) + '/' + faults.apply)}) {
    Set-Content -LiteralPath $Destination -Value 'partial copy'
    throw 'Injected apply failure'
  }
  if ((Matches-Suffix $Destination ${ps(faults.restore && '/' + faults.restore)}) -and $LiteralPath.Contains('transactions')) { throw 'Injected restore failure' }
  Microsoft.PowerShell.Management\Copy-Item @PSBoundParameters
  if (Matches-Suffix $LiteralPath ${ps(faults.corrupt && '/' + path.basename(sourceDir) + '/' + faults.corrupt)}) { Set-Content -LiteralPath $Destination -Value 'corrupt copy' }
}
function Remove-Item {
  [CmdletBinding()] param([Parameter(ValueFromPipelineByPropertyName=$true)][Alias('PSPath')][string]$LiteralPath, [switch]$Force, [switch]$Recurse)
  process {
    Microsoft.PowerShell.Management\Remove-Item @PSBoundParameters
    if (Matches-Suffix $LiteralPath ${ps(faults.remove && '/' + path.basename(installDir) + '/' + faults.remove)}) { throw 'Injected removal failure after deletion' }
  }
}
`;
  const file = write(root, "apply.ps1", wrapper + "\n" + script);
  const result = spawnSync(powershell, ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file], {
    encoding: "utf8", windowsHide: true, timeout: 120_000, env: shellEnv
  });
  if (result.error) throw result.error;
  return { ...result, log: (fs.existsSync(logPath) ? fs.readFileSync(logPath, "utf8") : "") + (fs.existsSync(trace) ? fs.readFileSync(trace, "utf8") : ""),
    launches: fs.existsSync(launches) ? fs.readFileSync(launches, "utf8") : "", updateRoot };
}

module.exports = { apply, write, snapshot, powershell, shellEnv, hasPowerShell };
