using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace PcTelemetry;

public static class DevelopmentProbe
{
    public static async Task<int> RunAsync(Options options)
    {
        if (!File.Exists(Path.Combine(options.Directory, "secrets", "identity.dpapi")) &&
            !File.Exists(Path.Combine(options.Directory, "identity.dpapi")))
            throw new InvalidOperationException("Start Collector with this data directory first.");
        using var identity = new LocalIdentity(options.Directory);
        using var socket = new ClientWebSocket();
        socket.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) =>
            certificate is not null && CryptographicOperations.FixedTimeEquals(
                SHA256.HashData(certificate.GetRawCertData()), SHA256.HashData(identity.Certificate.RawData));
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(options.Probe + 40));
        await socket.ConnectAsync(new Uri($"wss://127.0.0.1:{options.Port}/v1/stream"), timeout.Token);
        await socket.SendAsync(Protocol.Encode(new { type = "auth", version = 1,
            credentialId = identity.Secrets.CredentialId, token = identity.Secrets.Token }).AsMemory(),
            WebSocketMessageType.Text, true, timeout.Token);
        var authenticated = false;
        var count = 0;
        long previous = -1;
        while (count < options.Probe)
        {
            var text = await ReadTextAsync(socket, timeout.Token);
            using var document = JsonDocument.Parse(text);
            var type = document.RootElement.GetProperty("type").GetString();
            if (type == "auth.result")
            {
                authenticated = document.RootElement.GetProperty("success").GetBoolean();
                if (!authenticated) throw new InvalidDataException("Authentication rejected.");
            }
            else if (type == "hello" && options.Gpu is not null)
            {
                await socket.SendAsync(Protocol.Encode(new { type = "gpu.select", version = 1,
                    requestId = "probe-select", gpuId = options.Gpu }).AsMemory(),
                    WebSocketMessageType.Text, true, timeout.Token);
            }
            else if (type == "telemetry")
            {
                if (!authenticated) throw new InvalidDataException("Data arrived before authentication.");
                var snapshot = Protocol.Decode<Snapshot>(text);
                Protocol.Validate(snapshot);
                if (snapshot.DeviceId != identity.Secrets.DeviceId || snapshot.Mode != "live" ||
                    snapshot.Sequence <= previous)
                    throw new InvalidDataException("Invalid identity, mode or sequence.");
                previous = snapshot.Sequence;
                Console.WriteLine(text);
                count++;
            }
            else if (type == "error") throw new InvalidDataException("GPU selection rejected.");
        }
        Console.Error.WriteLine($"PASS: pinned TLS, authenticated WSS, {count} valid live snapshots.");
        socket.Abort();
        return 0;
    }

    public static async Task<string> ReadTextAsync(WebSocket socket, CancellationToken cancellation)
    {
        var buffer = new byte[16_384];
        var length = 0;
        while (true)
        {
            var result = await socket.ReceiveAsync(buffer.AsMemory(length), cancellation);
            if (result.MessageType != WebSocketMessageType.Text) throw new IOException("Expected text frame.");
            length += result.Count;
            if (result.EndOfMessage) return new UTF8Encoding(false, true).GetString(buffer, 0, length);
            if (length == buffer.Length) throw new InvalidDataException("Oversized message.");
        }
    }
}
