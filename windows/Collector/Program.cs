using System.Globalization;

namespace PcTelemetry;

public static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length == 0 || args.Contains("--tray"))
        {
            try { return TrayHost.Run(args.Where(arg => arg != "--tray").ToArray()); }
            catch (Exception ex)
            {
                MessageBox.Show("无法启动Collector。请检查端口、配置或是否已经运行。\n" + ex.Message, "PC Telemetry");
                return 1;
            }
        }
        return RunAsync(args).GetAwaiter().GetResult();
    }

    private static async Task<int> RunAsync(string[] args)
    {
        try
        {
            if (args.Length == 2 && args[0] == "--worker") { await WorkerProcess.RunAsync(args[1]); return 0; }
            var options = Options.Parse(args);
            if (options.Help)
            {
                Console.WriteLine("Collector --tray [--port 47631] [--data-dir PATH]");
                Console.WriteLine("Collector [--lan] [--port 47631] [--data-dir PATH] [--gpu ID] [--samples N] [--output FILE] [--diagnostics FILE]");
                Console.WriteLine("Collector --probe N [--port 47631] [--data-dir PATH] [--gpu ID]");
                return 0;
            }
            if (options.Probe > 0) return await DevelopmentProbe.RunAsync(options);
            await using var runtime = new CollectorRuntime(options);
            using var cancellation = new CancellationTokenSource();
            ConsoleCancelEventHandler handler = (_, e) => { e.Cancel = true; cancellation.Cancel(); };
            Console.CancelKeyPress += handler;
            try
            {
                Console.WriteLine($"WSS wss://127.0.0.1:{options.Port}/v1/stream");
                Console.WriteLine($"Certificate SHA-256 {runtime.Identity.Fingerprint}");
                Console.WriteLine($"Device {runtime.Identity.Secrets.DeviceId}");
                await runtime.RunAsync(cancellation.Token);
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
            finally { cancellation.Cancel(); Console.CancelKeyPress -= handler; }
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Collector stopped: {ex.GetType().Name}: {ex.Message}");
            return 1;
        }
    }
}

public sealed record Options(int Port, string Directory, string? Gpu, int Samples,
    int Probe, string? Diagnostics, string? Output, bool Help, bool Lan = false)
{
    public static Options Parse(string[] args)
    {
        if (args.Count(arg => arg == "--lan") > 1) throw new ArgumentException("Duplicate LAN option.");
        var lan = args.Contains("--lan");
        args = args.Where(arg => arg != "--lan").ToArray();
        var values = new Dictionary<string, string>();
        var allowed = new[] { "--port", "--data-dir", "--gpu", "--samples", "--probe", "--diagnostics", "--output" };
        if (args.Length == 1 && args[0] == "--help") return new(47631, "", null, 0, 0, null, null, true);
        for (var i = 0; i < args.Length; i += 2)
        {
            if (i + 1 >= args.Length || !allowed.Contains(args[i]) || !values.TryAdd(args[i], args[i + 1]))
                throw new ArgumentException("Invalid or duplicate option. Use --help.");
        }
        int Number(string name, int fallback)
            => values.TryGetValue(name, out var value) ? int.Parse(value, CultureInfo.InvariantCulture) : fallback;
        var port = Number("--port", 47631);
        var samples = Number("--samples", 0);
        var probe = Number("--probe", 0);
        if (port is < 1024 or > 65535 || samples < 0 || probe < 0 || probe > 3600)
            throw new ArgumentException("Option out of range.");
        var directory = Path.GetFullPath(values.GetValueOrDefault("--data-dir") ??
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PcTelemetry", "collector"));
        string? FileOption(string name) => values.TryGetValue(name, out var value) ? Path.GetFullPath(value) : null;
        return new(port, directory, values.GetValueOrDefault("--gpu"), samples, probe,
            FileOption("--diagnostics"), FileOption("--output"), false, lan);
    }
}

