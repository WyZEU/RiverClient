const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { buildApplyUpdateScript } = require("../../src/update-apply.js");

const powershell = process.env.RIVER_TEST_POWERSHELL || (process.platform === "win32" ? "powershell.exe" : "pwsh");
const hasPowerShell = !spawnSync(powershell, ["-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"], { windowsHide: true }).error;
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
  fs.rmSync(launches, { force: true });
  const script = buildScript({ pid: 0, sourceDir, installDir, relaunchExe: path.join(installDir, "RiverClient.exe"),
    relaunchFallbackExe: path.join(installDir, "RiverClient.exe"), logPath, updateRoot, mode, manifestPath });
  // Run the actual generated script with real filesystem cmdlets. Process control and
  // retry delays are suppressed; failures are injected at the filesystem boundary.
  const wrapper = String.raw`
function Start-Sleep { param($Seconds, $Milliseconds) }
function Wait-Process { param($Id, $ErrorAction) }
function Get-Process { param($Name, $ErrorAction) }
function Start-Process { param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle)
  Add-Content -LiteralPath ${ps(launches)} -Value ($FilePath + ' ' + ($ArgumentList -join ' '))
}
function Copy-Item {
  [CmdletBinding()] param([string]$LiteralPath, [string]$Destination, [switch]$Force)
  if ($LiteralPath -eq ${ps(faults.backup && path.join(installDir, faults.backup))} -and $Destination.Contains('transactions')) { throw 'Injected backup failure' }
  if ($LiteralPath -eq ${ps(faults.apply && path.join(sourceDir, faults.apply))}) {
    Set-Content -LiteralPath $Destination -Value 'partial copy'
    throw 'Injected apply failure'
  }
  if ($Destination -eq ${ps(faults.restore && path.join(installDir, faults.restore))} -and $LiteralPath.Contains('transactions')) { throw 'Injected restore failure' }
  Microsoft.PowerShell.Management\Copy-Item @PSBoundParameters
  if ($LiteralPath -eq ${ps(faults.corrupt && path.join(sourceDir, faults.corrupt))}) { Set-Content -LiteralPath $Destination -Value 'corrupt copy' }
}
function Remove-Item {
  [CmdletBinding()] param([Parameter(ValueFromPipelineByPropertyName=$true)][Alias('PSPath')][string]$LiteralPath, [switch]$Force, [switch]$Recurse)
  process {
    Microsoft.PowerShell.Management\Remove-Item @PSBoundParameters
    if ($LiteralPath -eq ${ps(faults.remove && path.join(installDir, faults.remove))}) { throw 'Injected removal failure after deletion' }
  }
}
`;
  const file = write(root, "apply.ps1", wrapper + "\n" + script);
  const result = spawnSync(powershell, ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file], {
    encoding: "utf8", windowsHide: true, timeout: 120_000
  });
  if (result.error) throw result.error;
  return { ...result, log: fs.existsSync(logPath) ? fs.readFileSync(logPath, "utf8") : "",
    launches: fs.existsSync(launches) ? fs.readFileSync(launches, "utf8") : "", updateRoot };
}

module.exports = { apply, write, snapshot, powershell, hasPowerShell };
