using System.Runtime.InteropServices;

namespace PcTelemetry;

public sealed class NativeMetrics
{
    private ulong? previousIdle;
    private ulong previousTotal;

    public Metric Cpu(DateTimeOffset at)
    {
        if (!GetSystemTimes(out var idle, out var kernel, out var user)) return Metric.Missing;
        var total = kernel + user;
        var oldIdle = previousIdle;
        var oldTotal = previousTotal;
        previousIdle = idle;
        previousTotal = total;
        if (oldIdle is null || total <= oldTotal || idle < oldIdle.Value) return Metric.Missing;
        var delta = total - oldTotal;
        return Metric.From(100.0 * (delta - Math.Min(delta, idle - oldIdle.Value)) / delta,
            at, "Windows.GetSystemTimes", true);
    }

    public static MemoryMetric Ram(DateTimeOffset at)
    {
        var status = new MemoryStatus { Length = (uint)Marshal.SizeOf<MemoryStatus>() };
        return GlobalMemoryStatusEx(ref status)
            ? MemoryMetric.From((long)(status.TotalPhysical - status.AvailablePhysical),
                (long)status.TotalPhysical, at, "Windows.GlobalMemoryStatusEx")
            : MemoryMetric.Missing;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryStatus
    {
        public uint Length;
        public uint Load;
        public ulong TotalPhysical;
        public ulong AvailablePhysical;
        public ulong TotalPageFile;
        public ulong AvailablePageFile;
        public ulong TotalVirtual;
        public ulong AvailableVirtual;
        public ulong AvailableExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);
    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemTimes(out ulong idle, out ulong kernel, out ulong user);
}
