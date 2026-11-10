using System.Security.Cryptography;
using System.Text;

namespace PcTelemetry;

public sealed class CollectorRuntime : IAsyncDisposable
{
    private readonly Options options;
    private readonly Mutex instance;
    private readonly WorkerSupervisor worker;
    private readonly Microsoft.AspNetCore.Builder.WebApplication server;
    private readonly PrivateNetwork network;
    public LocalIdentity Identity { get; }
    public PairingService Pairing { get; }
    public TelemetryService Telemetry { get; }
    public IReadOnlyList<System.Net.IPAddress> Addresses => network.Addresses;
    public bool DiscoveryStarted { get; private set; }

    public CollectorRuntime(Options options)
    {
        this.options = options;
        Directory.CreateDirectory(options.Directory);
        var name = "Local\\PcTelemetry-" + Convert.ToHexString(SHA256.HashData(
            Encoding.UTF8.GetBytes(Path.GetFullPath(options.Directory).ToUpperInvariant())));
        instance = new Mutex(true, name, out var acquired);
        if (!acquired) throw new InvalidOperationException("Collector already uses this data directory.");
        Identity = new LocalIdentity(options.Directory);
        Pairing = new PairingService(options.Directory);
        Telemetry = new TelemetryService(Identity.Secrets.DeviceId, options.Directory, options.Gpu);
        worker = new WorkerSupervisor();
        network = options.Lan ? PrivateNetwork.Read() : new([], []);
        server = WebSocketServer.Create(options.Port, Identity, Telemetry, Pairing, network.Addresses);
    }

    public async Task RunAsync(CancellationToken cancellation)
    {
        await server.StartAsync(cancellation);
        using var discovery = new DiscoveryAdvertisement(Identity.Secrets.DeviceId, options.Port, network);
        DiscoveryStarted = discovery.Started;
        try
        {
            await Telemetry.RunAsync(worker, options.Samples, options.Diagnostics, options.Output, cancellation);
        }
        finally
        {
            using var shutdown = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await server.StopAsync(shutdown.Token);
        }
    }

    public async ValueTask DisposeAsync()
    {
        await worker.DisposeAsync();
        await server.DisposeAsync();
        Identity.Dispose();
        instance.Dispose();
    }
}
