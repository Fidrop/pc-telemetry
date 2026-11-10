using System.Net;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace PcTelemetry;

public sealed record ClientMessage(string Type, int Version, string? CredentialId = null,
    string? Token = null, string? RequestId = null, string? GpuId = null, string? Code = null);

public static class WebSocketServer
{
    public static WebApplication Create(int port, LocalIdentity identity, TelemetryService telemetry,
        PairingService? pairing = null, IReadOnlyList<IPAddress>? addresses = null)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.Limits.MaxRequestBodySize = 16_384;
            foreach (var address in (addresses ?? [IPAddress.Loopback]).Append(IPAddress.Loopback).Distinct())
                options.Listen(address, port, listen => listen.UseHttps(https =>
            {
                https.ServerCertificate = identity.Certificate;
                https.SslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13;
            }));
        });
        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });
        var clients = new SemaphoreSlim(4);
        app.Lifetime.ApplicationStopped.Register(clients.Dispose);
        app.Map("/v1/stream", async context =>
        {
            if (!context.WebSockets.IsWebSocketRequest)
            {
                context.Response.StatusCode = 400;
                return;
            }
            var localClient = context.Connection.RemoteIpAddress is { } remote && IPAddress.IsLoopback(remote);
            if (!localClient && !PrivateNetwork.Read().Addresses.Contains(context.Connection.LocalIpAddress))
            {
                context.Response.StatusCode = 403;
                return;
            }
            if (!await clients.WaitAsync(0, context.RequestAborted))
            {
                context.Response.StatusCode = 429;
                return;
            }
            try
            {
                using var socket = await context.WebSockets.AcceptWebSocketAsync();
                await HandleAsync(socket, identity, telemetry, pairing, localClient, context.RequestAborted);
            }
            finally { clients.Release(); }
        });
        return app;
    }

    private static async Task HandleAsync(WebSocket socket, LocalIdentity identity,
        TelemetryService telemetry, PairingService? pairing, bool localClient, CancellationToken aborted)
    {
        using var connection = CancellationTokenSource.CreateLinkedTokenSource(aborted);
        using var sendGate = new SemaphoreSlim(1);
        System.Threading.Channels.Channel<Broadcast>? subscription = null;
        var counted = false;
        try
        {
            using var authTimeout = CancellationTokenSource.CreateLinkedTokenSource(connection.Token);
            authTimeout.CancelAfter(TimeSpan.FromSeconds(10));
            var auth = await ReceiveAsync(socket, authTimeout.Token);
            if (auth is { Type: "pair.request", Version: 1 })
            {
                IssuedCredential? credential = null;
                var paired = pairing is not null && auth.RequestId is { Length: > 0 and <= 128 } &&
                    pairing.TryPair(auth.Code, out credential);
                await SendAsync(socket, sendGate, Protocol.Encode(new {
                    type = "pair.result", version = 1, requestId = auth.RequestId, success = paired,
                    deviceId = telemetry.DeviceId, credentialId = credential?.CredentialId,
                    token = credential?.Token }), connection.Token);
                return;
            }
            bool Authorized() => auth is not null &&
                ((localClient && identity.Authenticate(auth.CredentialId, auth.Token)) ||
                    pairing?.Authenticate(auth.CredentialId, auth.Token) == true);
            if (auth is null || auth.Type != "auth" || auth.Version != 1 ||
                !Authorized())
            {
                await SendAsync(socket, sendGate, Protocol.Encode(new { type = "auth.result",
                    version = 1, success = false }), connection.Token);
                return;
            }
            telemetry.ClientConnected();
            counted = true;
            await SendAsync(socket, sendGate, Protocol.Encode(new { type = "auth.result",
                version = 1, success = true }), connection.Token);
            await SendAsync(socket, sendGate, Protocol.Encode(new { type = "hello", version = 1,
                deviceId = telemetry.DeviceId, sessionId = telemetry.SessionId,
                supportedVersions = new[] { 1 }, gpuList = telemetry.Gpus }), connection.Token);
            subscription = telemetry.Subscribe();
            var sendTask = SendSnapshotsAsync(socket, sendGate, subscription.Reader, telemetry, Authorized, connection.Token);
            var receiveTask = ReceiveCommandsAsync(socket, sendGate, telemetry, pairing, auth.CredentialId, connection.Token);
            await Task.WhenAny(sendTask, receiveTask);
            connection.Cancel();
            // Observe both tasks before disposing shared resources.
            await Task.WhenAll(sendTask, receiveTask);
        }
        catch (Exception ex) when (ex is OperationCanceledException or WebSocketException or
            JsonException or IOException or InvalidDataException)
        {
            // Per-connection failures do not terminate acquisition.
        }
        finally
        {
            connection.Cancel();
            if (counted) telemetry.ClientDisconnected();
            if (subscription is not null) telemetry.Unsubscribe(subscription);
            using var closeTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(1));
            try
            {
                if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Connection ended", closeTimeout.Token);
            }
            catch (Exception ex) when (ex is WebSocketException or OperationCanceledException)
            {
                socket.Abort();
            }
        }
    }

    private static async Task SendSnapshotsAsync(WebSocket socket, SemaphoreSlim gate,
        System.Threading.Channels.ChannelReader<Broadcast> reader, TelemetryService telemetry,
        Func<bool> authorized, CancellationToken cancellation)
    {
        await foreach (var update in reader.ReadAllAsync(cancellation))
        {
            if (!authorized()) throw new IOException("Credential revoked");
            await SendAsync(socket, gate, update.Json, cancellation,
                () => telemetry.SelectedGpu is null || update.Snapshot.Gpu?.Id == telemetry.SelectedGpu);
        }
    }

    private static async Task ReceiveCommandsAsync(WebSocket socket, SemaphoreSlim gate,
        TelemetryService telemetry, PairingService? pairing, string? credentialId, CancellationToken cancellation)
    {
        var window = System.Diagnostics.Stopwatch.StartNew();
        var count = 0;
        while (await ReceiveAsync(socket, cancellation) is { } command)
        {
            if (window.Elapsed >= TimeSpan.FromSeconds(1)) { window.Restart(); count = 0; }
            if (++count > 5) throw new InvalidDataException("Command rate exceeded");
            if (command is { Type: "unpair", Version: 1, RequestId.Length: > 0 and <= 128 })
            {
                if (credentialId is not null) pairing?.Revoke(credentialId);
                await SendAsync(socket, gate, Protocol.Encode(new { type = "unpair.result",
                    version = 1, requestId = command.RequestId, success = true }), cancellation);
                return;
            }
            if (command.Version != 1 || command.Type != "gpu.select" ||
                string.IsNullOrWhiteSpace(command.GpuId) ||
                string.IsNullOrWhiteSpace(command.RequestId) || command.RequestId.Length > 128)
                throw new InvalidDataException("Invalid command");
            var selected = telemetry.SelectGpu(command.GpuId);
            await SendAsync(socket, gate, Protocol.Encode(new { type = selected ? "gpu.selected" : "error",
                version = 1, requestId = command.RequestId, gpuId = selected ? command.GpuId : null,
                code = selected ? null : "unknown_gpu" }), cancellation);
        }
    }

    public static async Task<ClientMessage?> ReceiveAsync(WebSocket socket, CancellationToken cancellation)
    {
        var buffer = new byte[16_384];
        var length = 0;
        while (true)
        {
            var result = await socket.ReceiveAsync(buffer.AsMemory(length), cancellation);
            if (result.MessageType == WebSocketMessageType.Close) return null;
            if (result.MessageType != WebSocketMessageType.Text)
                throw new InvalidDataException("Only JSON text supported");
            length += result.Count;
            if (result.EndOfMessage)
                return Protocol.Decode<ClientMessage>(new UTF8Encoding(false, true).GetString(buffer, 0, length));
            if (length == buffer.Length) throw new InvalidDataException("Message too large");
        }
    }

    private static async Task SendAsync(WebSocket socket, SemaphoreSlim gate, byte[] message,
        CancellationToken cancellation, Func<bool>? stillValid = null)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromSeconds(3));
        await gate.WaitAsync(timeout.Token);
        try
        {
            if (stillValid is null || stillValid())
                await socket.SendAsync(message.AsMemory(), WebSocketMessageType.Text, true, timeout.Token);
        }
        finally { gate.Release(); }
    }
}
