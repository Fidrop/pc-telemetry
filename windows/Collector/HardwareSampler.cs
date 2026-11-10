using System.Diagnostics;
using LibreHardwareMonitor.Hardware;

namespace PcTelemetry;

public sealed record GroupTiming(string Id, double Milliseconds, string Status);
public sealed record WorkerRequest(string? GpuId, string[] ExcludedGroups);
public sealed record WorkerFrame(GpuInfo[] Gpus, GpuInfo? Gpu, Metrics Metrics, GroupTiming[] Timings);
public sealed record WorkerEvent(string Type, string? GroupId = null, WorkerFrame? Frame = null);

public sealed class GroupSchedule
{
    public int SlowCount { get; private set; }
    public DateTimeOffset NextDue { get; private set; }
    public bool IsDue(DateTimeOffset now) => now >= NextDue;
    public void Complete(DateTimeOffset now, double milliseconds, bool failed)
    {
        SlowCount = failed || milliseconds > 250 ? SlowCount + 1 : 0;
        NextDue = SlowCount >= 3 || failed ? now.AddSeconds(5) : now;
    }
}

public sealed class HardwareSampler : IDisposable
{
    private readonly Computer computer = new() { IsCpuEnabled = true, IsGpuEnabled = true };
    private readonly List<Group> groups = [];
    private readonly NativeMetrics native = new();

    private sealed class Group(IHardware hardware)
    {
        public IHardware Hardware { get; } = hardware;
        public string Id => Hardware.Identifier.ToString();
        public ISensor[]? Sensors { get; set; }
        public SensorValue[] Values { get; set; } = [];
        public DateTimeOffset? SampledAt { get; set; }
        public GroupSchedule Schedule { get; } = new();
        public bool Fresh { get; set; }
    }

    public void Open()
    {
        computer.Open();
        foreach (var hardware in computer.Hardware) Add(hardware);
    }

    private void Add(IHardware hardware)
    {
        if (hardware.HardwareType == HardwareType.Cpu ||
            hardware.HardwareType is HardwareType.GpuAmd or HardwareType.GpuIntel or HardwareType.GpuNvidia)
            groups.Add(new Group(hardware));
        foreach (var child in hardware.SubHardware) Add(child);
    }

    public async Task<WorkerFrame> SampleAsync(WorkerRequest request,
        Func<WorkerEvent, Task> progress)
    {
        var now = DateTimeOffset.UtcNow;
        var timings = new List<GroupTiming>();
        foreach (var group in groups)
        {
            group.Fresh = false;
            if (request.ExcludedGroups.Contains(group.Id) || !group.Schedule.IsDue(now)) continue;
            await progress(new WorkerEvent("updating", group.Id));
            var watch = Stopwatch.StartNew();
            var failed = false;
            try
            {
                group.Hardware.Update();
                // Cache the mapping after the first update, when lazy sensors are present.
                group.Sensors ??= group.Hardware.Sensors;
                group.Values = group.Sensors.Select(s => new SensorValue(s.Identifier.ToString(),
                    s.Name, s.SensorType.ToString(), s.Value)).ToArray();
                group.SampledAt = DateTimeOffset.UtcNow;
                group.Fresh = true;
            }
            catch (Exception ex) when (ex is not OutOfMemoryException)
            {
                failed = true;
            }
            watch.Stop();
            group.Schedule.Complete(now, watch.Elapsed.TotalMilliseconds, failed);
            timings.Add(new GroupTiming(group.Id, watch.Elapsed.TotalMilliseconds, failed ? "error" : "ok"));
        }

        var gpus = groups.Where(g => g.Hardware.HardwareType != HardwareType.Cpu)
            .Select(g => new GpuInfo(g.Id, g.Hardware.Name,
                SensorMapping.GpuKind(g.Hardware.HardwareType.ToString(), g.Hardware.Name))).ToArray();
        var selected = SensorMapping.SelectGpu(gpus, request.GpuId);
        var gpuGroup = groups.FirstOrDefault(g => g.Id == selected?.Id);
        var cpu = groups.FirstOrDefault(g => g.Hardware.HardwareType == HardwareType.Cpu);
        var fallback = native.Cpu(now);
        var cpuUsage = CpuValue(cpu, false);
        if (cpuUsage.Value is null) cpuUsage = fallback;
        var gpuUsage = GpuValue(gpuGroup, false);
        var gpuTemperature = GpuValue(gpuGroup, true);
        var vram = gpuGroup?.SampledAt is { } gpuAt && selected is not null
            ? SensorMapping.Vram(gpuGroup.Values, selected, gpuAt) : MemoryMetric.Missing;
        if (gpuGroup is { Fresh: false }) vram = vram.AsStale();
        return new WorkerFrame(gpus, selected,
            new Metrics(cpuUsage, gpuUsage, NativeMetrics.Ram(now), vram,
                CpuValue(cpu, true), gpuTemperature), timings.ToArray());
    }

    private static Metric CpuValue(Group? group, bool temperature)
    {
        if (group?.SampledAt is not { } at) return Metric.Missing;
        var metric = temperature ? SensorMapping.CpuTemperature(group.Values, at)
            : SensorMapping.CpuUsage(group.Values, at);
        return group.Fresh ? metric : metric.AsStale();
    }

    private static Metric GpuValue(Group? group, bool temperature)
    {
        if (group?.SampledAt is not { } at) return Metric.Missing;
        var metric = temperature ? SensorMapping.GpuTemperature(group.Values, at)
            : SensorMapping.GpuUsage(group.Values, at);
        return group.Fresh ? metric : metric.AsStale();
    }

    public void Dispose() => computer.Close();
}
