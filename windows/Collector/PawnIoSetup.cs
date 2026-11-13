using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Microsoft.Win32;
using Microsoft.Win32.SafeHandles;

namespace PcTelemetry;

public sealed record PawnIoStatus(Version? Version, bool Available)
{
    public bool Ready => Version >= new Version(2, 0) && Available;
    public static PawnIoStatus Read()
    {
        using var machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
        using var key = machine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\PawnIO");
        System.Version.TryParse(key?.GetValue("DisplayVersion") as string, out var version);
        if (version is null) return new(null, false);
        using var handle = OpenDevice(@"\\?\GLOBALROOT\Device\PawnIO", 0xC0000000, 3,
            IntPtr.Zero, 3, 0, IntPtr.Zero);
        return new(version, !handle.IsInvalid);
    }

    [DllImport("kernel32.dll", EntryPoint = "CreateFileW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle OpenDevice(string path, uint access, uint share,
        IntPtr security, uint creation, uint flags, IntPtr template);
}

public sealed class DriverSetupPreference(string directory)
{
    private string PathName => Path.Combine(directory, "temperature-setup-deferred");
    public bool ShouldPrompt(PawnIoStatus status) => !status.Ready && !File.Exists(PathName);
    public void Defer()
    {
        Directory.CreateDirectory(directory);
        LocalIdentity.AtomicWrite(PathName, System.Text.Encoding.UTF8.GetBytes(DateTimeOffset.UtcNow.ToString("o")));
    }
}

public enum DriverInstallResult { Ready, RestartWindowsRequired }
public sealed record DriverInstallProgress(string Message, int? Percent = null, bool Installing = false);

public sealed class PawnIoInstaller : IDisposable
{
    public const string DownloadUrl = "https://github.com/namazso/PawnIO.Setup/releases/download/2.2.0/PawnIO_setup.exe";
    public const string InstallerSha256 = "1F519A22E47187F70A1379A48CA604981C4FCF694F4E65B734AAA74A9FBA3032";
    private readonly HttpClient http;
    private readonly string expectedHash;
    private readonly Func<PawnIoStatus> readStatus;
    private readonly Action<string> verifySignature;
    private readonly Func<string, Task<int>> execute;
    private readonly string temporaryRoot;

    public PawnIoInstaller() : this(new HttpClient { Timeout = TimeSpan.FromMinutes(3) },
        InstallerSha256, PawnIoStatus.Read, Authenticode.Verify, RunInstallerAsync, Path.GetTempPath()) { }

    internal PawnIoInstaller(HttpClient http, string expectedHash, Func<PawnIoStatus> readStatus,
        Action<string> verifySignature, Func<string, Task<int>> execute, string temporaryRoot)
    {
        this.http = http; this.expectedHash = expectedHash; this.readStatus = readStatus;
        this.verifySignature = verifySignature; this.execute = execute; this.temporaryRoot = temporaryRoot;
    }

    public async Task<DriverInstallResult> InstallAsync(IProgress<DriverInstallProgress> progress,
        CancellationToken cancellation)
    {
        if (readStatus().Ready) return DriverInstallResult.Ready;
        var directory = Path.Combine(temporaryRoot, "PcTelemetry-Setup-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "PawnIO_setup.exe");
        try
        {
            progress.Report(new("正在从 PawnIO 官方下载温度采集驱动…"));
            using var response = await http.GetAsync(DownloadUrl, HttpCompletionOption.ResponseHeadersRead, cancellation);
            response.EnsureSuccessStatusCode();
            const int maximumBytes = 16 * 1024 * 1024;
            var length = response.Content.Headers.ContentLength;
            if (length > maximumBytes) throw new InvalidDataException("安装包大小异常，已停止安装。");
            await using (var source = await response.Content.ReadAsStreamAsync(cancellation))
            await using (var destination = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                65536, FileOptions.Asynchronous))
            {
                var buffer = new byte[65536];
                var total = 0;
                int count;
                while ((count = await source.ReadAsync(buffer, cancellation)) > 0)
                {
                    total += count;
                    if (total > maximumBytes) throw new InvalidDataException("安装包大小异常，已停止安装。");
                    await destination.WriteAsync(buffer.AsMemory(0, count), cancellation);
                    progress.Report(new("正在下载温度采集驱动…", length > 0 ? (int)Math.Min(100, total * 100L / length.Value) : null));
                }
            }
            // Keep the verified file locked against replacement through installation.
            using var lockedFile = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            progress.Report(new("正在校验安装包来源与数字签名…"));
            var hash = Convert.ToHexString(await SHA256.HashDataAsync(lockedFile, cancellation));
            if (!hash.Equals(expectedHash, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("安装包校验不一致，已停止安装。请稍后重试或更新 PC Telemetry。");
            verifySignature(path);
            cancellation.ThrowIfCancellationRequested();
            progress.Report(new("正在安装温度采集驱动，请稍候…", Installing: true));
            var exitCode = await execute(path);
            if (exitCode is 3010 or 1641) return DriverInstallResult.RestartWindowsRequired;
            if (exitCode != 0) throw new IOException($"驱动安装未完成（错误码 {exitCode}）。");
            var installed = readStatus();
            if (installed.Version is null || installed.Version < new Version(2, 0))
                throw new IOException("安装程序已退出，但未检测到所需驱动。请从托盘重试，或使用驱动官网的安装说明。");
            if (!installed.Ready) return DriverInstallResult.RestartWindowsRequired;
            return DriverInstallResult.Ready;
        }
        finally
        {
            try { if (File.Exists(path)) File.Delete(path); Directory.Delete(directory); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        }
    }

    private static async Task<int> RunInstallerAsync(string path)
    {
        using var process = Process.Start(new ProcessStartInfo(path)
        {
            UseShellExecute = false, CreateNoWindow = true, ArgumentList = { "-install" }
        }) ?? throw new IOException("无法启动驱动安装程序。");
        await process.WaitForExitAsync();
        return process.ExitCode;
    }
    public void Dispose() => http.Dispose();
}

internal static class Authenticode
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct FileInfo
    {
        public uint Size;
        [MarshalAs(UnmanagedType.LPWStr)] public string Path;
        public IntPtr File;
        public IntPtr Subject;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct TrustData
    {
        public uint Size;
        public IntPtr Policy;
        public IntPtr Sip;
        public uint Ui;
        public uint Revocation;
        public uint Choice;
        public IntPtr File;
        public uint StateAction;
        public IntPtr State;
        public IntPtr Url;
        public uint Flags;
        public uint Context;
        public IntPtr SignatureSettings;
    }
    [DllImport("wintrust.dll", ExactSpelling = true)]
    private static extern int WinVerifyTrust(IntPtr window, ref Guid action, ref TrustData data);

    public static void Verify(string path)
    {
        var file = new FileInfo { Size = (uint)Marshal.SizeOf<FileInfo>(), Path = path };
        var pointer = Marshal.AllocHGlobal(Marshal.SizeOf<FileInfo>());
        Marshal.StructureToPtr(file, pointer, false);
        var data = new TrustData { Size = (uint)Marshal.SizeOf<TrustData>(), Ui = 2, Choice = 1,
            File = pointer, StateAction = 1, Flags = 0x1000 };
        var action = new Guid("00AAC56B-CD44-11D0-8CC2-00C04FC295EE");
        try
        {
            if (WinVerifyTrust(new IntPtr(-1), ref action, ref data) != 0)
                throw new InvalidDataException("Windows 无法验证安装包数字签名，已停止安装。");
        }
        finally
        {
            data.StateAction = 2;
            WinVerifyTrust(new IntPtr(-1), ref action, ref data);
            Marshal.DestroyStructure<FileInfo>(pointer);
            Marshal.FreeHGlobal(pointer);
        }
    }
}
