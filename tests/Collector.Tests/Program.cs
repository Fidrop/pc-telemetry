using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PcTelemetry;

if (args.Length == 1 && args[0] == "--driver-download-check")
{
    await DriverSetupTests.DownloadCheckAsync();
    return;
}

if (args.Length == 2 && args[0] == "--pairing-host")
{
    await PairingFixtureHost.RunAsync(args[1]);
    return;
}

if (args.Length == 2 && args[0] == "--fixture-worker")
{
    await WorkerFixture.RunAsync(args[1]);
    return;
}

var passed = 0;
void Check(string name, bool condition)
{
    if (!condition) throw new Exception("FAIL " + name);
    Console.WriteLine("PASS " + name);
    passed++;
}
void Reject(string name, Action action)
{
    try { action(); }
    catch (JsonException) { Check(name, true); return; }
    throw new Exception("FAIL expected rejection: " + name);
}
var privateNetwork = PrivateNetwork.Read();
using (var profiles = new System.Management.ManagementObjectSearcher(@"root\StandardCimv2",
    "SELECT InterfaceIndex, NetworkCategory FROM MSFT_NetConnectionProfile"))
using (var results = profiles.Get())
{
    var privateIndexes = new HashSet<int>();
    foreach (System.Management.ManagementObject profile in results)
        using (profile)
            if (Convert.ToInt32(profile["NetworkCategory"]) is 1 or 2)
                privateIndexes.Add(Convert.ToInt32(profile["InterfaceIndex"]));
    var expectedIds = System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
        .Where(adapter => adapter.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up &&
            adapter.Supports(System.Net.NetworkInformation.NetworkInterfaceComponent.IPv4) &&
            privateIndexes.Contains(adapter.GetIPProperties().GetIPv4Properties().Index))
        .Select(adapter => adapter.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);
    Check("LAN adapter selection matches Windows private/domain profiles",
        expectedIds.SetEquals(privateNetwork.AdapterIds));
    Check("LAN addresses exclude loopback", privateNetwork.Addresses.All(address => !System.Net.IPAddress.IsLoopback(address)));
}
var at = DateTimeOffset.UtcNow;
var hardwareLibrary = typeof(LibreHardwareMonitor.Hardware.Computer).Assembly;
Check("Sensor library has no legacy Ring0 loader",
    hardwareLibrary.GetType("LibreHardwareMonitor.Hardware.Ring0") is null);
Check("Sensor library embeds no sys or WinRing0 driver resource",
    hardwareLibrary.GetManifestResourceNames().All(name =>
        !name.EndsWith(".sys", StringComparison.OrdinalIgnoreCase) &&
        !name.Contains("WinRing0", StringComparison.OrdinalIgnoreCase)));
var registryPath = @"Software\PcTelemetryTests\" + Guid.NewGuid().ToString("N");
try
{
    using var isolatedRoot = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(registryPath);
    var registration = new StartupRegistration(isolatedRoot, @"C:\Program Files\PC Telemetry\Collector.exe", "Run");
    Check("Autostart starts disabled in isolated key", !registration.Enabled);
    registration.SetEnabled(true);
    Check("Autostart quotes paths containing spaces", registration.Enabled &&
        registration.Command == "\"C:\\Program Files\\PC Telemetry\\Collector.exe\" --tray");
    registration.SetEnabled(false);
    Check("Autostart can be disabled", !registration.Enabled);
}
finally { Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(registryPath, false); }
SensorValue Sensor(string name, string type, double? value) => new("fixture/" + name, name, type, value);
var cpu = new[] { Sensor("CPU Core #1", "Load", 10), Sensor("CPU Total", "Load", 42),
    Sensor("CPU Package", "Temperature", 55), Sensor("CPU Core #1", "Temperature", 80) };
Check("CPU total mapping", SensorMapping.CpuUsage(cpu, at).Value == 42);
Check("CPU Package priority", SensorMapping.CpuTemperature(cpu, at).Value == 55);
Check("CPU hottest core fallback", SensorMapping.CpuTemperature(cpu.Skip(3).ToArray(), at).Value == 80);
Check("CPU missing stays null", SensorMapping.CpuUsage([], at).Value is null);
Check("CPU invalid load rejected", SensorMapping.CpuUsage([Sensor("CPU Total", "Load", 101)], at).Value is null);
Check("GPU core mapping", SensorMapping.GpuUsage([Sensor("GPU Core", "Load", 66)], at).Value == 66);
Check("GPU temperature mapping", SensorMapping.GpuTemperature([Sensor("GPU Core", "Temperature", 62)], at).Value == 62);
Check("Non-finite sensor rejected", Metric.From(double.NaN, at, "fixture").Value is null);
var integrated = new GpuInfo("igpu", "Intel integrated", "integrated");
var discrete = new GpuInfo("dgpu", "NVIDIA fixture", "discrete");
var memorySensors = new[] { Sensor("GPU Memory Used", "SmallData", 2048),
    Sensor("GPU Memory Total", "SmallData", 8192) };
var vram = SensorMapping.Vram(memorySensors, discrete, at);
Check("Dedicated VRAM bytes and percent", vram.UsedBytes == 2147483648L && vram.Percent == 25);
Check("Integrated shared memory excluded", SensorMapping.Vram(memorySensors, integrated, at).UsedBytes is null);
Check("Missing VRAM total stays null",
    SensorMapping.Vram(memorySensors.Take(1).ToArray(), discrete, at).UsedBytes is null);
Check("Invalid capacity rejected", MemoryMetric.From(20, 10, at, "fixture").Percent is null);
Check("Zero total rejected", MemoryMetric.From(0, 0, at, "fixture").Percent is null);
Check("Zero usage is valid", MemoryMetric.From(0, 10, at, "fixture").Percent == 0);
Check("Default discrete GPU", SensorMapping.SelectGpu([integrated, discrete], null) == discrete);
Check("Manual GPU selection", SensorMapping.SelectGpu([integrated, discrete], "igpu") == integrated);
Check("Unknown GPU does not select another", SensorMapping.SelectGpu([integrated, discrete], "missing") is null);
Check("Intel mobile Arc stays integrated", SensorMapping.GpuKind("GpuIntel", "Intel(R) Arc(TM) 130V GPU (8GB)") == "integrated");
Check("Intel discrete Arc recognized", SensorMapping.GpuKind("GpuIntel", "Intel Arc A770") == "discrete");
var schedule = new GroupSchedule();
schedule.Complete(at, 300, false);
schedule.Complete(at.AddSeconds(1), 300, false);
schedule.Complete(at.AddSeconds(2), 300, false);
Check("Three slow updates throttle group", !schedule.IsDue(at.AddSeconds(6)) && schedule.IsDue(at.AddSeconds(7)));
schedule.Complete(at.AddSeconds(7), 10, false);
Check("Fast recovery restores 1Hz", schedule.IsDue(at.AddSeconds(8)));
schedule.Complete(at.AddSeconds(8), 1, true);
Check("Failure throttles group", !schedule.IsDue(at.AddSeconds(10)));
Check("Stale retains original time and value",
    Metric.From(42, at, "fixture").AsStale() == new Metric(42, "stale", at, "fixture"));
var fixtureRoot = Path.GetFullPath(args.Length > 0 ? args[0] : ".");
foreach (var file in Directory.GetFiles(Path.Combine(fixtureRoot, "protocol", "examples"), "*.json"))
{
    var snapshot = Protocol.Decode<Snapshot>(File.ReadAllText(file));
    Protocol.Validate(snapshot);
    var roundTrip = Protocol.Decode<Snapshot>(Encoding.UTF8.GetString(Protocol.Encode(snapshot)));
    Check("Protocol round trip " + Path.GetFileName(file), snapshot == roundTrip);
}
var fixture = Protocol.Decode<Snapshot>(File.ReadAllText(Path.Combine(fixtureRoot, "protocol/examples/telemetry.demo.json")));
Reject("Unknown protocol version", () => Protocol.Validate(fixture with { Version = 2 }));
Reject("Out-of-range percent", () => Protocol.Validate(fixture with
    { Metrics = fixture.Metrics with { CpuUsage = new Metric(101, "ok", at, "fixture") } }));
Reject("Unavailable value rejected", () => Protocol.Validate(fixture with
    { Metrics = fixture.Metrics with { CpuTemperature = new Metric(0, "unavailable", null, null) } }));
Reject("Capacity mismatch rejected", () => Protocol.Validate(fixture with
    { Metrics = fixture.Metrics with { Ram = new MemoryMetric(5, 10, 90, "ok", at, "fixture") } }));
Reject("Unknown JSON fields rejected", () => Protocol.Decode<Snapshot>(
    Encoding.UTF8.GetString(Protocol.Encode(fixture)).Replace("\"version\":1", "\"version\":1,\"unknown\":true")));
Reject("Required sequence field enforced", () => Protocol.Decode<Snapshot>(
    Encoding.UTF8.GetString(Protocol.Encode(fixture)).Replace("\"sequence\":1,", "")));

var testDirectory = Path.Combine(fixtureRoot, "runtime", "tests-" + Guid.NewGuid().ToString("N"));
using var identity = new LocalIdentity(testDirectory);
using var reloadedIdentity = new LocalIdentity(testDirectory);
Check("DPAPI identity and certificate persist", identity.Fingerprint == reloadedIdentity.Fingerprint &&
    identity.Secrets.Token == reloadedIdentity.Secrets.Token);
Check("Invalid token rejected", !identity.Authenticate(identity.Secrets.CredentialId, "invalid"));
Check("Valid token accepted", identity.Authenticate(identity.Secrets.CredentialId, identity.Secrets.Token));
var identityBytes = File.ReadAllBytes(Path.Combine(testDirectory, "secrets", "identity.dpapi"));
Check("Token absent from stored ciphertext", !Encoding.UTF8.GetString(identityBytes).Contains(identity.Secrets.Token));
var service = new TelemetryService(identity.Secrets.DeviceId, testDirectory, null);
var frame = new WorkerFrame([integrated, discrete], discrete, fixture.Metrics, []);
service.Publish(frame, 1);
var queue = service.Subscribe();
service.Publish(frame, 2);
service.Publish(frame, 3);
Check("Slow subscriber retains latest only", queue.Reader.TryRead(out var newest) &&
    newest.Snapshot.Sequence == 3 && !queue.Reader.TryRead(out _));
service.Unsubscribe(queue);
Check("GPU choice rejects unknown", !service.SelectGpu("invalid"));
Check("GPU choice persists", service.SelectGpu("igpu") &&
    new TelemetryService(identity.Secrets.DeviceId, testDirectory, null).SelectedGpu == "igpu");
service.SelectGpu("dgpu");
var pairingTime = DateTimeOffset.UtcNow;
var pairing = new PairingService(testDirectory, () => pairingTime);
var pairingWindow = pairing.OpenWindow();
for (var attempt = 0; attempt < 5; attempt++) pairing.TryPair("invalid", out _);
Check("Pairing locks after five failures", !pairing.TryPair(pairingWindow.Code, out _));
pairingWindow = pairing.OpenWindow();
pairingTime = pairingTime.AddSeconds(120);
Check("Pairing expires at 120 seconds", !pairing.TryPair(pairingWindow.Code, out _));
pairingWindow = pairing.OpenWindow();
Check("One-time pairing issues random credential", pairing.TryPair(pairingWindow.Code, out var issued) &&
    issued is not null && Convert.FromBase64String(issued.Token).Length == 32);
Check("Pairing code consumed once", !pairing.TryPair(pairingWindow.Code, out _));
Check("Paired credential authenticates", pairing.Authenticate(issued!.CredentialId, issued.Token));
Check("Paired credential persists under DPAPI", new PairingService(testDirectory).Authenticate(issued.CredentialId, issued.Token));
pairing.Revoke(issued.CredentialId);
Check("Revocation invalidates credential", !pairing.Authenticate(issued.CredentialId, issued.Token));
pairingWindow = pairing.OpenWindow();
var successfulPairs = 0;
Parallel.For(0, 8, index => { if (pairing.TryPair(pairingWindow.Code, out _)) Interlocked.Increment(ref successfulPairs); });
Check("Concurrent pairing consumes code atomically", successfulPairs == 1);
pairingWindow = pairing.OpenWindow();
pairing.CloseWindow();
Check("Closing pairing window cancels code", !pairing.TryPair(pairingWindow.Code, out _));

await using var server = WebSocketServer.Create(0, identity, service, pairing);
await server.StartAsync();
var address = new Uri(server.Urls.Single().Replace("https://", "wss://") + "/v1/stream");
using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(30));
ClientWebSocket Client(bool validPin)
{
    var client = new ClientWebSocket();
    client.Options.RemoteCertificateValidationCallback = (_, certificate, _, _) => validPin &&
        certificate is not null && CryptographicOperations.FixedTimeEquals(
            SHA256.HashData(certificate.GetRawCertData()), SHA256.HashData(identity.Certificate.RawData));
    return client;
}
async Task Send(ClientWebSocket client, object value)
    => await client.SendAsync(Protocol.Encode(value).AsMemory(), WebSocketMessageType.Text, true, deadline.Token);
async Task<JsonElement> Receive(ClientWebSocket client, string wanted)
{
    for (var i = 0; i < 10; i++)
    {
        using var doc = JsonDocument.Parse(await DevelopmentProbe.ReadTextAsync(client, deadline.Token));
        if (doc.RootElement.GetProperty("type").GetString() == wanted) return doc.RootElement.Clone();
    }
    throw new Exception("Missing message " + wanted);
}
using (var wrongPin = Client(false))
{
    var rejected = false;
    try { await wrongPin.ConnectAsync(address, deadline.Token); }
    catch (WebSocketException ex)
    {
        Exception? cause = ex;
        while (cause is not null)
        {
            if (cause is System.Security.Authentication.AuthenticationException) rejected = true;
            cause = cause.InnerException;
        }
    }
    Check("TLS wrong certificate pin rejected", rejected);
}
using (var badAuth = Client(true))
{
    await badAuth.ConnectAsync(address, deadline.Token);
    await Send(badAuth, new { type = "auth", version = 1, credentialId = identity.Secrets.CredentialId, token = "wrong" });
    var response = await Receive(badAuth, "auth.result");
    Check("WSS incorrect credential rejected", !response.GetProperty("success").GetBoolean());
}
using (var client = Client(true))
{
    await client.ConnectAsync(address, deadline.Token);
    await Send(client, new { type = "auth", version = 1, credentialId = identity.Secrets.CredentialId, token = identity.Secrets.Token });
    Check("WSS valid credential accepted", (await Receive(client, "auth.result")).GetProperty("success").GetBoolean());
    var legacyHello = await Receive(client, "hello");
    Check("Authenticated hello lists GPUs", legacyHello.GetProperty("gpuList").GetArrayLength() == 2);
    Check("Legacy hello has no new field for strict clients", !legacyHello.TryGetProperty("deviceName", out _));
    var snapshot = Protocol.Decode<Snapshot>((await Receive(client, "telemetry")).GetRawText());
    Protocol.Validate(snapshot);
    Check("WSS snapshot uses expected identity", snapshot.DeviceId == identity.Secrets.DeviceId);
    await Send(client, new { type = "gpu.select", version = 1, requestId = "select-1", gpuId = "igpu" });
    Check("GPU selection acknowledgement", (await Receive(client, "gpu.selected")).GetProperty("gpuId").GetString() == "igpu");
    service.Publish(frame, 4); // An old-GPU acquisition already in flight when selection changed.
    await Task.Delay(100);
    await Send(client, new { type = "gpu.select", version = 1, requestId = "select-2", gpuId = "invalid" });
    using var nextMessage = JsonDocument.Parse(await DevelopmentProbe.ReadTextAsync(client, deadline.Token));
    Check("Old GPU snapshot suppressed after acknowledgement", nextMessage.RootElement.GetProperty("type").GetString() == "error");
    Check("Unknown GPU command returns error", nextMessage.RootElement.GetProperty("code").GetString() == "unknown_gpu");
    service.Publish(frame with { Gpu = integrated }, 5);
    Check("Selected GPU resumes telemetry", (await Receive(client, "telemetry")).GetProperty("gpu").GetProperty("id").GetString() == "igpu");
    client.Abort();
}
var wireWindow = pairing.OpenWindow();
string pairedId;
string pairedToken;
using (var pairClient = Client(true))
{
    await pairClient.ConnectAsync(address, deadline.Token);
    await Send(pairClient, new { type = "pair.request", version = 1, requestId = "pair-wire", code = wireWindow.Code });
    var result = await Receive(pairClient, "pair.result");
    Check("Pinned WSS pairing returns credential once", result.GetProperty("success").GetBoolean());
    Check("Legacy pairing has no new field for strict clients", !result.TryGetProperty("deviceName", out _));
    pairedId = result.GetProperty("credentialId").GetString()!;
    pairedToken = result.GetProperty("token").GetString()!;
}
using (var pairedClient = Client(true))
{
    pairedClient.Options.SetRequestHeader("X-PC-Telemetry-Features", "device-name");
    await pairedClient.ConnectAsync(address, deadline.Token);
    await Send(pairedClient, new { type = "auth", version = 1, credentialId = pairedId, token = pairedToken });
    Check("Paired WSS credential authenticates", (await Receive(pairedClient, "auth.result")).GetProperty("success").GetBoolean());
    Check("Opted-in hello provides Windows hostname",
        (await Receive(pairedClient, "hello")).GetProperty("deviceName").GetString() == Environment.MachineName);
    await Receive(pairedClient, "telemetry");
    await Send(pairedClient, new { type = "unpair", version = 1, requestId = "remove-pair" });
    Check("Authenticated unpair acknowledged", (await Receive(pairedClient, "unpair.result")).GetProperty("success").GetBoolean());
    Check("Unpair removes long-term credential", !pairing.Authenticate(pairedId, pairedToken));
}
await server.StopAsync();
await using (var worker = new WorkerSupervisor(pipeName =>
{
    var executable = Path.Combine(AppContext.BaseDirectory, "Collector.Tests.exe");
    return System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(executable)
    {
        UseShellExecute = false, CreateNoWindow = true,
        ArgumentList = { "--fixture-worker", pipeName }
    }) ?? throw new Exception("Cannot launch fixture worker");
}, TimeSpan.FromMilliseconds(300)))
{
    var first = await worker.SampleAsync(null, deadline.Token);
    Check("Worker pipe receives fixture snapshot", first.Metrics.CpuUsage.Value == 42);
    var timedOut = await worker.SampleAsync(null, deadline.Token);
    Check("Hung worker is recycled and retains stale data",
        worker.RestartCount == 1 && timedOut.Metrics.CpuUsage.Status == "stale" &&
        timedOut.Metrics.CpuUsage.Value == 42);
    await Task.Delay(2200, deadline.Token);
    var recovered = await worker.SampleAsync(null, deadline.Token);
    Check("Replacement worker excludes quarantined group",
        recovered.Timings.Any(t => t.Id == "fixture-hung" && t.Status == "excluded"));
}
await DriverSetupTests.RunAsync(Check, fixtureRoot);
Console.WriteLine($"RESULT {passed} checks passed. Hardware compatibility and performance are tested separately.");
