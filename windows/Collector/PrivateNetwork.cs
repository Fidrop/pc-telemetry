using System.Management;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PcTelemetry;

public sealed record PrivateNetwork(string[] AdapterIds, IPAddress[] Addresses)
{
    public static PrivateNetwork Read()
    {
        var indexes = new HashSet<int>();
        try
        {
            // CIM exposes adapter indexes directly, avoiding IDispatch GUID return marshaling.
            using var searcher = new ManagementObjectSearcher(@"root\StandardCimv2",
                "SELECT InterfaceIndex, NetworkCategory FROM MSFT_NetConnectionProfile");
            searcher.Options.Timeout = TimeSpan.FromSeconds(5);
            using var profiles = searcher.Get();
            foreach (ManagementObject profile in profiles)
            {
                using (profile)
                {
                    if (Convert.ToInt32(profile["NetworkCategory"]) is 1 or 2)
                        indexes.Add(Convert.ToInt32(profile["InterfaceIndex"]));
                }
            }
        }
        catch (Exception ex) when (ex is ManagementException or COMException or UnauthorizedAccessException)
        {
            // Fail closed: loopback service remains usable if Windows profile lookup fails.
            return new([], []);
        }

        var adapters = NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up &&
                adapter.Supports(NetworkInterfaceComponent.IPv4) &&
                indexes.Contains(adapter.GetIPProperties().GetIPv4Properties().Index)).ToArray();
        var addresses = adapters.SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(address => address.Address)
            .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
            .Distinct().ToArray();
        return new(adapters.Select(adapter => adapter.Id).ToArray(), addresses);
    }
}
