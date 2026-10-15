import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { win32 } from "node:path";
import { HOST } from "../../host.ts";

// Host-only static code. The pipe name travels as JSON on stdin, never as executable source.
// GetNamedPipeServerProcessId on a client handle and process replacement were verified on
// an owned Windows11 fixture. This witness does not make a separate mutation RPC atomic.
const probe = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class CollieLocalPipeWitness {
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint pid);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool GetProcessTimes(IntPtr process, out long created, out long exited, out long kernel, out long user);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool CloseHandle(IntPtr handle);
    public static string Read(SafePipeHandle pipe) {
        uint pid;
        if (!GetNamedPipeServerProcessId(pipe, out pid) || pid == 0) throw new InvalidOperationException();
        IntPtr process = OpenProcess(0x1000, false, pid);
        if (process == IntPtr.Zero) throw new InvalidOperationException();
        try {
            long created, exited, kernel, user;
            if (!GetProcessTimes(process, out created, out exited, out kernel, out user) || created <= 0) throw new InvalidOperationException();
            return pid.ToString() + ":" + created.ToString();
        } finally { CloseHandle(process); }
    }
}
'@
$actual = [Console]::ReadLine() | ConvertFrom-Json
if ($actual -isnot [string] -or !$actual.StartsWith('\\.\pipe\') -or $actual.Length -le 9 -or $actual.Length -gt 4096 -or $actual -match '[\x00-\x1f\x7f]') { exit 1 }
$client = [System.IO.Pipes.NamedPipeClientStream]::new('.', $actual.Substring(9), [System.IO.Pipes.PipeDirection]::InOut)
try {
    $client.Connect(250)
    [Console]::WriteLine([CollieLocalPipeWitness]::Read($client.SafePipeHandle))
} catch { exit 1 } finally { $client.Dispose() }
`;

const encodedProbe = Buffer.from(probe, "utf16le").toString("base64");

/** Bounded synchronous local identity fence; failures retire readiness at the caller. */
export function readWindowsPipeWitness(socketPath: string): string | null {
  if (HOST.platform !== "win32") return null;
  const prefix = "\\\\.\\pipe\\";
  const canonical = socketPath.startsWith(prefix) ? socketPath
    : socketPath.startsWith("//./pipe/") ? prefix + socketPath.slice(9)
    : prefix + socketPath;
  if (canonical.length <= prefix.length || Buffer.byteLength(canonical, "utf8") > 4096 || [...canonical].some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)) return null;
  const executable = win32.join(process.env.SystemRoot ?? "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
  try {
    const result = spawnSync(executable, ["-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedProbe], {
      input: JSON.stringify(canonical) + "\n", encoding: "utf8", timeout: 2500,
      maxBuffer: 4096, windowsHide: true, stdio: ["pipe", "pipe", "ignore"],
    });
    if (result.error || result.status !== 0 || !result.stdout) return null;
    const value = result.stdout.trim();
    if (!/^[1-9][0-9]{0,9}:[1-9][0-9]{0,18}$/.test(value)) return null;
    const pid = Number(value.split(":", 1)[0]);
    if (!Number.isInteger(pid) || pid > 0xffff_ffff) return null;
    return createHash("sha256").update(`herdr-local-pipe\0${canonical}\0${value}`).digest("hex");
  } catch { return null; }
}
