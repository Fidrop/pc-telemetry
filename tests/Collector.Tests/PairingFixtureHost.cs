using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using PcTelemetry;

internal static class PairingFixtureHost
{
    public static async Task RunAsync(string pipeName)
    {
        var directory = Environment.GetEnvironmentVariable("PC_TELEMETRY_TEST_DIRECTORY")
            ?? throw new InvalidOperationException("Missing test directory");
        await using var runtime = new CollectorRuntime(Options.Parse(["--data-dir", directory]));
        using var cancellation = new CancellationTokenSource();
        var execution = runtime.RunAsync(cancellation.Token);
        using var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await pipe.ConnectAsync(15_000);
        using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, true) { AutoFlush = true };
        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, true);
        var window = runtime.Pairing.OpenWindow();
        var information = new { endpoint = "wss://127.0.0.1:47631/v1/stream",
            fingerprint = runtime.Identity.Fingerprint, code = window.Code, deviceId = runtime.Identity.Secrets.DeviceId };
        await writer.WriteLineAsync(JsonSerializer.Serialize(information, Protocol.Json));
        try { await reader.ReadLineAsync(); }
        finally
        {
            cancellation.Cancel();
            try { await execution; } catch (OperationCanceledException) { }
        }
    }
}
