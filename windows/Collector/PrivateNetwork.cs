using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PcTelemetry;

public sealed record PrivateNetwork(string[] AdapterIds, IPAddress[] Addresses)
{
    public static PrivateNetwork Read()
    {
        var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        object? manager = null;
        try
        {
            var type = Type.GetTypeFromCLSID(new Guid("DCB00C01-570F-4A9B-8D69-199FDBA5723B"));
            manager = Activator.CreateInstance(type ?? throw new InvalidOperationException());
            dynamic networks = ((dynamic)manager!).GetNetworks(1);
            foreach (dynamic network in networks)
            {
                try
                {
                    var category = (int)network.GetCategory();
                    if (category is not (1 or 2)) continue; // Private or domain-authenticated.
                    foreach (dynamic connection in network.GetNetworkConnections())
                    {
                        try { ids.Add(((Guid)connection.GetAdapterId()).ToString("B")); }
                        finally { Marshal.ReleaseComObject(connection); }
                    }
                }
                finally { Marshal.ReleaseComObject(network); }
            }
            Marshal.ReleaseComObject(networks);
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException or
            Microsoft.CSharp.RuntimeBinder.RuntimeBinderException)
        {
            return new([], []);
        }
        finally { if (manager is not null) Marshal.ReleaseComObject(manager); }

        var addresses = NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => ids.Contains(adapter.Id) && adapter.OperationalStatus == OperationalStatus.Up)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(address => address.Address)
            .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
            .Distinct().ToArray();
        return new(ids.ToArray(), addresses);
    }
}
