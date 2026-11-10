using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.Json.Serialization.Metadata;

namespace PcTelemetry;

public sealed record Metric(double? Value, string Status, DateTimeOffset? SampledAt, string? Source)
{
    public static Metric Missing => new(null, "unavailable", null, null);
    public Metric AsStale() => Value is null ? Missing : this with { Status = "stale" };
    public static Metric From(double? value, DateTimeOffset at, string source, bool percent = false)
        => value is { } number && double.IsFinite(number) && (!percent || number is >= 0 and <= 100)
            ? new(number, "ok", at, source) : Missing;
}

public sealed record MemoryMetric(long? UsedBytes, long? TotalBytes, double? Percent,
    string Status, DateTimeOffset? SampledAt, string? Source)
{
    public static MemoryMetric Missing => new(null, null, null, "unavailable", null, null);
    public MemoryMetric AsStale() => UsedBytes is null ? Missing : this with { Status = "stale" };
    public static MemoryMetric From(long? used, long? total, DateTimeOffset at, string source)
        => used is >= 0 && total is > 0 && used <= total && total <= Protocol.MaxInteger
            ? new(used, total, 100.0 * used / total, "ok", at, source) : Missing;
}

public sealed record GpuInfo(string Id, string Name, string Kind);
public sealed record Metrics(Metric CpuUsage, Metric GpuUsage, MemoryMetric Ram,
    MemoryMetric Vram, Metric CpuTemperature, Metric GpuTemperature)
{
    public static Metrics Missing => new(Metric.Missing, Metric.Missing, MemoryMetric.Missing,
        MemoryMetric.Missing, Metric.Missing, Metric.Missing);
    public Metrics AsStale() => new(CpuUsage.AsStale(), GpuUsage.AsStale(), Ram.AsStale(),
        Vram.AsStale(), CpuTemperature.AsStale(), GpuTemperature.AsStale());
}
public sealed record Snapshot(string Type, int Version, string DeviceId, string SessionId,
    long Sequence, DateTimeOffset SampledAt, string Mode, GpuInfo? Gpu, Metrics Metrics);

public static class Protocol
{
    public const long MaxInteger = 9_007_199_254_740_991;
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        PropertyNameCaseInsensitive = false,
        TypeInfoResolver = new DefaultJsonTypeInfoResolver
        {
            Modifiers =
            {
                info =>
                {
                    if (info.Type == typeof(Snapshot) || info.Type == typeof(Metrics) ||
                        info.Type == typeof(Metric) || info.Type == typeof(MemoryMetric) ||
                        info.Type == typeof(GpuInfo))
                        foreach (var property in info.Properties) property.IsRequired = true;
                }
            }
        }
    };

    public static byte[] Encode<T>(T value) => JsonSerializer.SerializeToUtf8Bytes(value, Json);
    public static T Decode<T>(string json) => JsonSerializer.Deserialize<T>(json, Json)
        ?? throw new JsonException("Missing object");

    public static void Validate(Snapshot snapshot)
    {
        if (snapshot.Type != "telemetry" || snapshot.Version != 1 ||
            string.IsNullOrWhiteSpace(snapshot.DeviceId) || string.IsNullOrWhiteSpace(snapshot.SessionId) ||
            snapshot.Sequence is < 0 or > MaxInteger || snapshot.Mode is not ("live" or "demo") ||
            snapshot.SampledAt == default || snapshot.Metrics is null)
            throw new JsonException("Invalid envelope");
        if (snapshot.Gpu is { } gpu && (string.IsNullOrWhiteSpace(gpu.Id) ||
            string.IsNullOrWhiteSpace(gpu.Name) || gpu.Kind is not ("discrete" or "integrated" or "unknown")))
            throw new JsonException("Invalid GPU");
        ValidateMetric(snapshot.Metrics.CpuUsage, true);
        ValidateMetric(snapshot.Metrics.GpuUsage, true);
        ValidateMetric(snapshot.Metrics.CpuTemperature, false);
        ValidateMetric(snapshot.Metrics.GpuTemperature, false);
        ValidateMemory(snapshot.Metrics.Ram);
        ValidateMemory(snapshot.Metrics.Vram);
    }

    private static void ValidateMetric(Metric? metric, bool percent)
    {
        if (metric is null) throw new JsonException("Missing metric");
        if (metric.Status == "unavailable")
        {
            if (metric.Value is not null || metric.SampledAt is not null)
                throw new JsonException("Unavailable metric has a value");
            return;
        }
        if (metric.Status is not ("ok" or "stale") || metric.Value is not { } value ||
            !double.IsFinite(value) || metric.SampledAt is null || metric.SampledAt == default ||
            string.IsNullOrWhiteSpace(metric.Source) || (percent && value is < 0 or > 100))
            throw new JsonException("Invalid metric");
    }

    private static void ValidateMemory(MemoryMetric? metric)
    {
        if (metric is null) throw new JsonException("Missing memory");
        if (metric.Status == "unavailable")
        {
            if (metric.UsedBytes is not null || metric.TotalBytes is not null ||
                metric.Percent is not null || metric.SampledAt is not null)
                throw new JsonException("Unavailable memory has a value");
            return;
        }
        if (metric.Status is not ("ok" or "stale") || metric.SampledAt is null ||
            metric.SampledAt == default || string.IsNullOrWhiteSpace(metric.Source) ||
            metric.UsedBytes is not >= 0 || metric.TotalBytes is not > 0 ||
            metric.TotalBytes > MaxInteger || metric.UsedBytes > metric.TotalBytes ||
            metric.Percent is not { } percent || !double.IsFinite(percent) ||
            Math.Abs(100.0 * metric.UsedBytes.Value / metric.TotalBytes.Value - percent) > 0.05)
            throw new JsonException("Invalid memory");
    }
}
