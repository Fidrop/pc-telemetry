using System.Threading.Channels;

namespace PcTelemetry;

public sealed record Broadcast(Snapshot Snapshot, byte[] Json);
public sealed class TelemetryService
{
    private readonly object gate = new();
    private readonly HashSet<Channel<Broadcast>> subscribers = [];
    private readonly string gpuPath;
    private string? selectedGpu;
    private GpuInfo[] gpus = [];
    private Broadcast? latest;
    public string DeviceId { get; }
    public string SessionId { get; } = Guid.NewGuid().ToString();

    public TelemetryService(string deviceId, string directory, string? gpuId)
    {
        DeviceId = deviceId;
        gpuPath = Path.Combine(directory, "gpu.json");
        selectedGpu = gpuId ?? (File.Exists(gpuPath)
            ? System.Text.Json.JsonSerializer.Deserialize<string>(File.ReadAllText(gpuPath)) : null);
    }

    public GpuInfo[] Gpus { get { lock (gate) return gpus.ToArray(); } }
    public Broadcast? Latest { get { lock (gate) return latest; } }
    public string? SelectedGpu { get { lock (gate) return selectedGpu; } }

    public bool SelectGpu(string id)
    {
        lock (gate)
        {
            if (!gpus.Any(g => g.Id == id)) return false;
            LocalIdentity.AtomicWrite(gpuPath, Protocol.Encode(id));
            selectedGpu = id;
            return true;
        }
    }

    public Channel<Broadcast> Subscribe()
    {
        var channel = Channel.CreateBounded<Broadcast>(new BoundedChannelOptions(1)
        {
            FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true
        });
        lock (gate)
        {
            subscribers.Add(channel);
            if (latest is not null) channel.Writer.TryWrite(latest);
        }
        return channel;
    }

    public void Unsubscribe(Channel<Broadcast> channel)
    {
        lock (gate) subscribers.Remove(channel);
        channel.Writer.TryComplete();
    }

    public void Publish(WorkerFrame frame, long sequence)
    {
        lock (gate)
        {
            gpus = frame.Gpus;
            if (gpus.Length > 0 && selectedGpu is not null && !gpus.Any(g => g.Id == selectedGpu))
            {
                LocalIdentity.AtomicWrite(gpuPath, Protocol.Encode<string?>(null));
                selectedGpu = null;
            }
            var snapshot = new Snapshot("telemetry", 1, DeviceId, SessionId, sequence,
                DateTimeOffset.UtcNow, "live", frame.Gpu, frame.Metrics);
            Protocol.Validate(snapshot);
            latest = new Broadcast(snapshot, Protocol.Encode(snapshot));
            foreach (var subscriber in subscribers) subscriber.Writer.TryWrite(latest);
        }
    }

    public async Task RunAsync(WorkerSupervisor worker, int sampleLimit, string? diagnostics, string? output,
        CancellationToken cancellation)
    {
        var native = new NativeMetrics();
        long sequence = 0;
        do
        {
            var cycle = System.Diagnostics.Stopwatch.StartNew();
            var fallbackAt = DateTimeOffset.UtcNow;
            var fallbackCpu = native.Cpu(fallbackAt);
            var frame = await worker.SampleAsync(SelectedGpu, cancellation);
            frame = frame with { Metrics = frame.Metrics with { Ram = NativeMetrics.Ram(DateTimeOffset.UtcNow) } };
            if (frame.Metrics.CpuUsage.Value is null || frame.Metrics.CpuUsage.Status != "ok")
                frame = frame with { Metrics = frame.Metrics with { CpuUsage = fallbackCpu } };
            Publish(frame, ++sequence);
            if (output is not null)
                await File.AppendAllTextAsync(output, System.Text.Encoding.UTF8.GetString(Latest!.Json)
                    + Environment.NewLine, cancellation);
            if (diagnostics is not null)
            {
                var entry = new { sequence, timestamp = DateTimeOffset.UtcNow, frame.Timings,
                    worker.RestartCount };
                await File.AppendAllTextAsync(diagnostics,
                    System.Text.Json.JsonSerializer.Serialize(entry, Protocol.Json) + Environment.NewLine, cancellation);
            }
            if (sampleLimit > 0 && sequence >= sampleLimit) return;
            var remaining = TimeSpan.FromSeconds(1) - cycle.Elapsed;
            // Long cycles discard the missed deadline, without an immediate catch-up sample.
            await Task.Delay(remaining > TimeSpan.Zero ? remaining : TimeSpan.FromSeconds(1), cancellation);
        } while (!cancellation.IsCancellationRequested);
    }
}
