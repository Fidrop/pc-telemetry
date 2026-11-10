using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using PcTelemetry;

internal static class WorkerFixture
{
    // Synthetic fault injection belongs exclusively to the test executable.
    public static async Task RunAsync(string name)
    {
        using var pipe = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous);
        await pipe.ConnectAsync(10_000);
        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, true);
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, true) { AutoFlush = true };
        await writer.WriteLineAsync(JsonSerializer.Serialize(new WorkerEvent("ready"), Protocol.Json));
        var count = 0;
        while (await reader.ReadLineAsync() is { } line)
        {
            var request = Protocol.Decode<WorkerRequest>(line);
            if (++count == 2)
            {
                await writer.WriteLineAsync(JsonSerializer.Serialize(
                    new WorkerEvent("updating", "fixture-hung"), Protocol.Json));
                await Task.Delay(Timeout.Infinite);
            }
            var metrics = Metrics.Missing with { CpuUsage = Metric.From(42, DateTimeOffset.UtcNow, "fixture") };
            var timings = request.ExcludedGroups.Contains("fixture-hung")
                ? new[] { new GroupTiming("fixture-hung", 0, "excluded") } : [];
            var frame = new WorkerFrame([], null, metrics, timings);
            await writer.WriteLineAsync(JsonSerializer.Serialize(new WorkerEvent("frame", Frame: frame), Protocol.Json));
        }
    }
}
