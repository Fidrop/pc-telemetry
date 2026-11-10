using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace PcTelemetry;

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        try
        {
            if (args.Length == 2 && args[0] == "--worker")
            {
                await WorkerProcess.RunAsync(args[1]);
                return 0;
            }
            var options = Options.Parse(args);
            if (options.Help)
            {
                Console.WriteLine("Collector [--port 47631] [--data-dir PATH] [--gpu ID] [--samples N] [--output FILE] [--diagnostics FILE]");
                Console.WriteLine("Collector --probe N [--port 47631] [--data-dir PATH] [--gpu ID]");
                Console.WriteLine("Stage 1 uses pinned WSS on 127.0.0.1 and a per-user DPAPI-protected development credential.");
                return 0;
            }
            if (options.Probe > 0)
                return await DevelopmentProbe.RunAsync(options);

            Directory.CreateDirectory(options.Directory);
            var mutexName = "Local\\PcTelemetry-" + Convert.ToHexString(
                SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(options.Directory).ToUpperInvariant())));
            using var instance = new Mutex(true, mutexName, out var acquired);
            if (!acquired) throw new InvalidOperationException("Collector already uses this data directory.");
            using var identity = new LocalIdentity(options.Directory);
            var service = new TelemetryService(identity.Secrets.DeviceId, options.Directory, options.Gpu);
            await using var worker = new WorkerSupervisor();
            await using var server = WebSocketServer.Create(options.Port, identity, service);
            using var cancellation = new CancellationTokenSource();
            ConsoleCancelEventHandler handler = (_, e) => { e.Cancel = true; cancellation.Cancel(); };
            Console.CancelKeyPress += handler;
            try
            {
                await server.StartAsync(cancellation.Token);
                Console.WriteLine($"WSS wss://127.0.0.1:{options.Port}/v1/stream");
                Console.WriteLine($"Certificate SHA-256 {identity.Fingerprint}");
                Console.WriteLine($"Device {identity.Secrets.DeviceId}");
                await service.RunAsync(worker, options.Samples, options.Diagnostics, options.Output, cancellation.Token);
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
            finally
            {
                cancellation.Cancel();
                using var shutdown = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                await server.StopAsync(shutdown.Token);
                Console.CancelKeyPress -= handler;
            }
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
    int Probe, string? Diagnostics, string? Output, bool Help)
{
    public static Options Parse(string[] args)
    {
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
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PcTelemetry", "development"));
        string? FileOption(string name) => values.TryGetValue(name, out var value) ? Path.GetFullPath(value) : null;
        return new(port, directory, values.GetValueOrDefault("--gpu"), samples, probe,
            FileOption("--diagnostics"), FileOption("--output"), false);
    }
}
