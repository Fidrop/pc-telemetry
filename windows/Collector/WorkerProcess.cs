using System.Diagnostics;
using System.IO.Pipes;
using System.Reflection;
using System.Text;

namespace PcTelemetry;

public static class WorkerProcess
{
    public static async Task RunAsync(string pipeName)
    {
        using var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await pipe.ConnectAsync(10_000);
        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, true);
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, true) { AutoFlush = true };
        using var sampler = new HardwareSampler();
        sampler.Open();
        await writer.WriteLineAsync(System.Text.Json.JsonSerializer.Serialize(new WorkerEvent("ready"), Protocol.Json));
        while (await reader.ReadLineAsync() is { } line)
        {
            var request = Protocol.Decode<WorkerRequest>(line);
            var frame = await sampler.SampleAsync(request, message =>
                writer.WriteLineAsync(System.Text.Json.JsonSerializer.Serialize(message, Protocol.Json)));
            await writer.WriteLineAsync(System.Text.Json.JsonSerializer.Serialize(new WorkerEvent("frame", Frame: frame), Protocol.Json));
        }
    }
}

public sealed class WorkerSupervisor : IAsyncDisposable
{
    private readonly WorkerJob job = new();
    private readonly Func<string, Process> startWorker;
    private readonly TimeSpan updateTimeout;
    private Process? process;
    private NamedPipeServerStream? pipe;
    private StreamReader? reader;
    private StreamWriter? writer;
    private readonly Dictionary<string, DateTimeOffset> quarantine = [];
    private WorkerFrame last = new([], null, Metrics.Missing, []);
    private DateTimeOffset retryAt;
    private int failures;
    private string? updatingGroup;
    public int RestartCount { get; private set; }

    public WorkerSupervisor(Func<string, Process>? startWorker = null, TimeSpan? updateTimeout = null)
    {
        this.startWorker = startWorker ?? StartWorker;
        this.updateTimeout = updateTimeout ?? TimeSpan.FromSeconds(2);
    }

    public async Task<WorkerFrame> SampleAsync(string? gpuId, CancellationToken cancellation)
    {
        try
        {
            if (process is null)
            {
                if (DateTimeOffset.UtcNow < retryAt) return StaleFrame(gpuId);
                await StartAsync(cancellation);
            }
            var exclusions = quarantine.Where(pair => pair.Value > DateTimeOffset.UtcNow)
                .Select(pair => pair.Key).ToArray();
            using var cycleDeadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            cycleDeadline.CancelAfter(updateTimeout);
            var request = new WorkerRequest(gpuId, exclusions);
            await writer!.WriteLineAsync(System.Text.Json.JsonSerializer.Serialize(request, Protocol.Json)
                .AsMemory(), cycleDeadline.Token);
            while (true)
            {
                var message = await ReadAsync(updateTimeout, cycleDeadline.Token);
                if (message.Type == "updating") updatingGroup = message.GroupId;
                else if (message.Type == "frame" && message.Frame is { } frame)
                {
                    last = frame;
                    updatingGroup = null;
                    failures = 0;
                    return frame;
                }
                else throw new InvalidDataException("Invalid worker event");
            }
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or
            System.Text.Json.JsonException or InvalidOperationException or OperationCanceledException)
        {
            if (updatingGroup is not null)
                quarantine[updatingGroup] = DateTimeOffset.UtcNow.AddSeconds(60);
            await StopAsync();
            cancellation.ThrowIfCancellationRequested();
            failures++;
            RestartCount++;
            retryAt = DateTimeOffset.UtcNow.AddSeconds(Math.Min(30, Math.Pow(2, Math.Min(failures, 5))));
            Console.Error.WriteLine("Sensor worker unavailable; retaining stale values and scheduling recovery.");
            return StaleFrame(gpuId);
        }
    }

    private WorkerFrame StaleFrame(string? gpuId)
    {
        var selected = SensorMapping.SelectGpu(last.Gpus, gpuId);
        var metrics = last.Metrics.AsStale();
        if (last.Gpu?.Id != selected?.Id)
            metrics = metrics with { GpuUsage = Metric.Missing, GpuTemperature = Metric.Missing,
                Vram = MemoryMetric.Missing };
        return last with { Gpu = selected, Metrics = metrics, Timings = [] };
    }

    private async Task StartAsync(CancellationToken cancellation)
    {
        var name = "pc-telemetry-" + Guid.NewGuid().ToString("N");
        pipe = new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
            PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
        process = startWorker(name);
        job.Assign(process);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromSeconds(20));
        await pipe.WaitForConnectionAsync(timeout.Token);
        reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, true);
        writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, true) { AutoFlush = true };
        var ready = await ReadAsync(TimeSpan.FromSeconds(20), cancellation);
        if (ready.Type != "ready") throw new InvalidDataException("Worker not ready");
    }

    private static Process StartWorker(string name)
    {
        var executable = Path.Combine(Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location)!, "Collector.exe");
        return Process.Start(new ProcessStartInfo(executable)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            ArgumentList = { "--worker", name }
        }) ?? throw new InvalidOperationException("Cannot start sensor worker");
    }

    private async Task<WorkerEvent> ReadAsync(TimeSpan timeout, CancellationToken cancellation)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(timeout);
        var line = await reader!.ReadLineAsync(deadline.Token)
            ?? throw new IOException("Worker disconnected");
        if (line.Length > 1_048_576) throw new InvalidDataException("Oversized worker response");
        return Protocol.Decode<WorkerEvent>(line);
    }

    private async Task StopAsync()
    {
        writer?.Dispose();
        reader?.Dispose();
        pipe?.Dispose();
        writer = null;
        reader = null;
        pipe = null;
        if (process is not null)
        {
            try
            {
                if (!process.HasExited) process.Kill(true);
                await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
            }
            finally
            {
                process.Dispose();
                process = null;
            }
        }
        updatingGroup = null;
    }

    public async ValueTask DisposeAsync()
    {
        try { await StopAsync(); }
        finally { job.Dispose(); }
    }
}
