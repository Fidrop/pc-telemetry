namespace PcTelemetry;

public sealed record SensorValue(string Id, string Name, string Type, double? Value);

public static class SensorMapping
{
    public static Metric CpuUsage(IEnumerable<SensorValue> sensors, DateTimeOffset at)
        => Read(sensors, "Load", ["CPU Total"], at, true);
    public static Metric GpuUsage(IEnumerable<SensorValue> sensors, DateTimeOffset at)
        => Read(sensors, "Load", ["GPU Core", "D3D 3D"], at, true);

    public static Metric CpuTemperature(IReadOnlyList<SensorValue> sensors, DateTimeOffset at)
    {
        var primary = Read(sensors, "Temperature",
            ["CPU Package", "Core (Tctl/Tdie)", "Core (Tdie)", "CPU (Tctl/Tdie)"], at, false);
        if (primary.Value is not null) return primary;
        var hottest = sensors.Where(s => s.Type == "Temperature" &&
            s.Name.StartsWith("CPU Core", StringComparison.OrdinalIgnoreCase) &&
            s.Value is { } value && double.IsFinite(value))
            .OrderByDescending(s => s.Value).FirstOrDefault();
        return hottest is null ? Metric.Missing : Metric.From(hottest.Value, at, hottest.Id);
    }

    public static Metric GpuTemperature(IEnumerable<SensorValue> sensors, DateTimeOffset at)
        => Read(sensors, "Temperature", ["GPU Core"], at, false);

    private static Metric Read(IEnumerable<SensorValue> sensors, string type,
        string[] names, DateTimeOffset at, bool percent)
    {
        var candidates = sensors.ToArray();
        foreach (var name in names)
        {
            var sensor = candidates.FirstOrDefault(s => s.Type == type &&
                s.Name.Equals(name, StringComparison.OrdinalIgnoreCase) &&
                s.Value is { } value && double.IsFinite(value) &&
                (!percent || value is >= 0 and <= 100));
            if (sensor is not null) return Metric.From(sensor.Value, at, sensor.Id, percent);
        }
        return Metric.Missing;
    }

    public static MemoryMetric Vram(IReadOnlyList<SensorValue> sensors, GpuInfo gpu, DateTimeOffset at)
    {
        // Generic GPU memory on integrated adapters can describe shared system RAM.
        var pairs = gpu.Kind == "discrete"
            ? new[] { ("GPU Memory Used", "GPU Memory Total"),
                      ("D3D Dedicated Memory Used", "D3D Dedicated Memory Total") }
            : new[] { ("D3D Dedicated Memory Used", "D3D Dedicated Memory Total") };
        foreach (var (usedName, totalName) in pairs)
        {
            var used = sensors.FirstOrDefault(s => s.Name == usedName && s.Type is "SmallData" or "Data");
            var total = sensors.FirstOrDefault(s => s.Name == totalName && s.Type is "SmallData" or "Data");
            if (used is null || total is null) continue;
            var result = MemoryMetric.From(Bytes(used), Bytes(total), at, used.Id + "|" + total.Id);
            if (result.UsedBytes is not null) return result;
        }
        return MemoryMetric.Missing;
    }

    private static long? Bytes(SensorValue sensor)
    {
        var factor = sensor.Type == "SmallData" ? 1024.0 * 1024 : 1024.0 * 1024 * 1024;
        var bytes = sensor.Value * factor;
        return bytes is >= 0 and <= Protocol.MaxInteger && double.IsFinite(bytes.Value)
            ? (long)Math.Round(bytes.Value) : null;
    }

    public static GpuInfo? SelectGpu(IEnumerable<GpuInfo> gpus, string? id)
    {
        var candidates = gpus.ToArray();
        if (id is not null) return candidates.FirstOrDefault(g => g.Id == id);
        return candidates.FirstOrDefault(g => g.Kind == "discrete") ?? candidates.FirstOrDefault();
    }

    public static string GpuKind(string hardwareType, string name)
    {
        if (hardwareType == "GpuNvidia" ||
            name.Contains("Radeon RX", StringComparison.OrdinalIgnoreCase) ||
            name.Contains("Radeon Pro", StringComparison.OrdinalIgnoreCase) ||
            System.Text.RegularExpressions.Regex.IsMatch(name, @"\bArc(?:\(TM\))?\s+[AB]\d{3}\b",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase))
            return "discrete";
        if (hardwareType == "GpuIntel") return "integrated";
        return "unknown";
    }
}
