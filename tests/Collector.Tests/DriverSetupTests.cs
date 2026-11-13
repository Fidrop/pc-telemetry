using System.Net;
using System.Security.Cryptography;
using System.Windows.Forms;
using PcTelemetry;

internal static class DriverSetupTests
{
    public static async Task DownloadCheckAsync()
    {
        var ready = false;
        var signatureVerified = false;
        var root = Path.GetTempPath();
        using var installer = new PawnIoInstaller(new HttpClient { Timeout = TimeSpan.FromMinutes(3) },
            PawnIoInstaller.InstallerSha256, () => new PawnIoStatus(ready ? new Version(2, 2) : null, ready),
            path => { Authenticode.Verify(path); signatureVerified = true; },
            _ => { ready = true; return Task.FromResult(0); }, root);
        var result = await installer.InstallAsync(new Reporter(), CancellationToken.None);
        if (result != DriverInstallResult.Ready || !signatureVerified) throw new Exception("Official download check failed");
        Console.WriteLine("PASS Real official HTTPS download, fixed SHA256, native Windows Authenticode validation and cleanup.");
        Console.WriteLine("Controlled process boundary: installer was not executed; the installed system driver was unchanged.");
    }

    private sealed class DownloadFixture(byte[] bytes, bool fail = false) : HttpMessageHandler
    {
        public int Requests { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellation)
        {
            cancellation.ThrowIfCancellationRequested();
            Requests++;
            if (request.RequestUri?.AbsoluteUri != PawnIoInstaller.DownloadUrl) throw new Exception("Unexpected download source");
            return Task.FromResult(new HttpResponseMessage(fail ? HttpStatusCode.ServiceUnavailable : HttpStatusCode.OK)
            { Content = new ByteArrayContent(bytes) });
        }
    }
    private sealed class Reporter : IProgress<DriverInstallProgress>
    {
        public bool Installing { get; private set; }
        public void Report(DriverInstallProgress value) { if (value.Installing) Installing = true; }
    }
    public static async Task RunAsync(Action<string, bool> check, string projectRoot)
    {
        var root = Path.Combine(Path.GetTempPath(), "PcTelemetry-SetupTests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var missing = new PawnIoStatus(null, false);
        var ready = new PawnIoStatus(new Version(2, 1), true);
        try
        {
            var preference = new DriverSetupPreference(root);
            check("Missing driver prompts on first startup", preference.ShouldPrompt(missing));
            check("Installed usable driver skips setup", !preference.ShouldPrompt(ready));
            check("Old or unavailable drivers are not ready", !new PawnIoStatus(new Version(1, 0), true).Ready &&
                !new PawnIoStatus(new Version(2, 2), false).Ready);
            preference.Defer();
            check("Deferring setup survives restart", !new DriverSetupPreference(root).ShouldPrompt(missing));

            var payload = "Controlled installer fixture; never executed"u8.ToArray();
            var hash = Convert.ToHexString(SHA256.HashData(payload));
            var reporter = new Reporter();
            var installed = false;
            var signatureChecks = 0;
            var executions = 0;
            using (var handler = new DownloadFixture(payload))
            using (var installer = new PawnIoInstaller(new HttpClient(handler), hash,
                () => installed ? ready : missing, _ => signatureChecks++, _ =>
                { executions++; installed = true; return Task.FromResult(0); }, root))
            {
                var result = await installer.InstallAsync(reporter, CancellationToken.None);
                check("Confirmed setup verifies then installs and rechecks availability",
                    result == DriverInstallResult.Ready && signatureChecks == 1 && executions == 1 && reporter.Installing);
                await installer.InstallAsync(reporter, CancellationToken.None);
                check("Ready driver is not downloaded or installed again", handler.Requests == 1 && executions == 1);
            }
            foreach (var scenario in new[] { "hash", "signature", "network", "exit", "missing-after-exit", "cancel" })
            {
                executions = 0;
                using var handler = new DownloadFixture(payload, scenario == "network");
                using var installer = new PawnIoInstaller(new HttpClient(handler), scenario == "hash" ? "0".PadLeft(64, '0') : hash,
                    () => missing, _ => { if (scenario == "signature") throw new InvalidDataException("Untrusted signature fixture"); },
                    _ => { executions++; return Task.FromResult(scenario == "exit" ? 5 : 0); }, root);
                using var cancel = new CancellationTokenSource();
                if (scenario == "cancel") cancel.Cancel();
                var rejected = false;
                try { await installer.InstallAsync(reporter, cancel.Token); }
                catch (Exception ex) when (ex is IOException or InvalidDataException or HttpRequestException or OperationCanceledException) { rejected = true; }
                check("Setup handles " + scenario + " without claiming success", rejected &&
                    (scenario is "exit" or "missing-after-exit" || executions == 0));
            }
            foreach (var exitCode in new[] { 3010, 1641, 0 })
            {
                using var installer = new PawnIoInstaller(new HttpClient(new DownloadFixture(payload)), hash,
                    () => new PawnIoStatus(new Version(2, 2), false), _ => { }, _ => Task.FromResult(exitCode), root);
                check("Setup preserves Windows restart requirement " + exitCode,
                    await installer.InstallAsync(reporter, CancellationToken.None) == DriverInstallResult.RestartWindowsRequired);
            }
            check("Setup cleans its temporary installer folders", Directory.GetDirectories(root).Length == 0);
            var unsignedPath = Path.Combine(root, "unsigned-fixture.bin");
            await File.WriteAllBytesAsync(unsignedPath, payload);
            var unsignedRejected = false;
            try { Authenticode.Verify(unsignedPath); } catch (InvalidDataException) { unsignedRejected = true; }
            check("Native Windows signature verifier rejects unsigned content", unsignedRejected);
            var dotnetHost = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(typeof(object).Assembly.Location)!, "..", "..", "..", "dotnet.exe"));
            Authenticode.Verify(dotnetHost);
            check("Native Windows signature verifier accepts trusted signed executable", true);
            File.Delete(unsignedPath);

            Exception? uiFailure = null;
            var thread = new Thread(() =>
            {
                try
                {
                    Application.EnableVisualStyles();
                    Application.SetCompatibleTextRenderingDefault(false);
                    Application.SetDefaultFont(new System.Drawing.Font("Segoe UI", 9));
                    var directory = Path.Combine(projectRoot, "artifacts", "driver-setup-qa");
                    Directory.CreateDirectory(directory);
                    foreach (var fixture in new[] { "missing", "ready", "failure", "success" })
                    {
                        var calls = 0;
                        using var dialog = new DriverSetupDialog(() => fixture == "ready" ? ready : missing, (_, _) =>
                        {
                            calls++;
                            if (fixture == "failure") throw new IOException("Controlled download failure");
                            return Task.FromResult(DriverInstallResult.Ready);
                        });
                        using var timer = new System.Windows.Forms.Timer { Interval = 100 };
                        dialog.Shown += (_, _) =>
                        {
                            if (fixture is "failure" or "success") Find<Button>(dialog, "安装并继续").PerformClick();
                            if (fixture != "success") timer.Start();
                        };
                        timer.Tick += (_, _) =>
                        {
                            timer.Stop();
                            using var bitmap = new System.Drawing.Bitmap(dialog.Width, dialog.Height);
                            dialog.DrawToBitmap(bitmap, dialog.ClientRectangle with { Width = dialog.Width, Height = dialog.Height });
                            bitmap.Save(Path.Combine(directory, fixture + ".png"));
                            if (fixture == "missing") check("Missing-driver explanation remains visible",
                                Find<Label>(dialog, "尚未安装温度采集驱动。").Height >= 12);
                            if (fixture == "failure") check("Failed setup offers retry and defer", Find<Button>(dialog, "安装并继续").Enabled &&
                                Find<Button>(dialog, "暂不安装").Enabled);
                            dialog.Close();
                        };
                        var result = dialog.ShowDialog();
                        check("Setup dialog " + fixture + " respects user action",
                            fixture is "missing" or "ready" ? calls == 0 : fixture == "success" ? calls == 1 && result == DialogResult.OK : calls == 1);
                    }
                }
                catch (Exception ex) { uiFailure = ex; }
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            if (!thread.Join(TimeSpan.FromSeconds(20))) throw new TimeoutException("Driver setup UI fixtures timed out");
            if (uiFailure is not null) throw new Exception("Driver setup UI fixture failed", uiFailure);
        }
        finally
        {
            File.Delete(Path.Combine(root, "temperature-setup-deferred"));
            File.Delete(Path.Combine(root, "unsigned-fixture.bin"));
            Directory.Delete(root);
        }
    }
    private static T Find<T>(Control root, string text) where T : Control
    {
        foreach (Control control in root.Controls)
        {
            if (control is T typed && control.Text == text) return typed;
            if (control.HasChildren)
            {
                var found = TryFind<T>(control, text);
                if (found is not null) return found;
            }
        }
        throw new Exception("Missing setup control: " + text);
    }
    private static T? TryFind<T>(Control root, string text) where T : Control
    {
        foreach (Control control in root.Controls)
        {
            if (control is T typed && control.Text == text) return typed;
            var found = TryFind<T>(control, text);
            if (found is not null) return found;
        }
        return null;
    }
}
