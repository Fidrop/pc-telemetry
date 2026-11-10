using Makaretu.Dns;

namespace PcTelemetry;

public sealed class DiscoveryAdvertisement : IDisposable
{
    private MulticastService? multicast;
    private ServiceDiscovery? discovery;
    public bool Started { get; private set; }
    public DiscoveryAdvertisement(string deviceId, int port, PrivateNetwork network)
    {
        if (network.Addresses.Length == 0) return;
        try
        {
            multicast = new MulticastService(interfaces =>
                interfaces.Where(adapter => network.AdapterIds.Contains(adapter.Id,
                    StringComparer.OrdinalIgnoreCase)));
            discovery = new ServiceDiscovery(multicast);
            var service = new ServiceProfile("PC-Telemetry-" + deviceId[..8],
                "_pc-telemetry._tcp", (ushort)port, network.Addresses);
            service.AddProperty("id", deviceId);
            service.AddProperty("version", "1");
            discovery.Advertise(service);
            multicast.Start();
            Started = true;
        }
        catch (Exception ex) when (ex is System.Net.Sockets.SocketException or InvalidOperationException)
        {
            Dispose();
        }
    }
    public void Dispose()
    {
        discovery?.Unadvertise();
        discovery?.Dispose();
        multicast?.Dispose();
        discovery = null;
        multicast = null;
    }
}
