using System.Globalization;
using System.Security.Cryptography;

namespace PcTelemetry;

public sealed record StoredCredential(string Id, string TokenHash, DateTimeOffset CreatedAt);
public sealed record IssuedCredential(string CredentialId, string Token);
public sealed record PairingWindow(string Code, DateTimeOffset ExpiresAt);

public sealed class PairingService
{
    private readonly object gate = new();
    private readonly string path;
    private readonly Func<DateTimeOffset> clock;
    private Dictionary<string, StoredCredential> credentials;
    private PairingWindow? window;
    private int failures;

    public PairingService(string directory, Func<DateTimeOffset>? clock = null)
    {
        this.clock = clock ?? (() => DateTimeOffset.UtcNow);
        path = Path.Combine(directory, "secrets", "credentials.dpapi");
        if (File.Exists(path))
        {
            var plaintext = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
            try
            {
                credentials = Protocol.Decode<Dictionary<string, StoredCredential>>(
                    System.Text.Encoding.UTF8.GetString(plaintext));
            }
            finally { CryptographicOperations.ZeroMemory(plaintext); }
        }
        else credentials = [];
    }

    public PairingWindow OpenWindow()
    {
        lock (gate)
        {
            failures = 0;
            return window = new PairingWindow(
                RandomNumberGenerator.GetInt32(100_000_000).ToString("D8", CultureInfo.InvariantCulture),
                clock().AddSeconds(120));
        }
    }
    public bool WindowActive
    {
        get { lock (gate) return window is not null && clock() < window.ExpiresAt && failures < 5; }
    }
    public void CloseWindow() { lock (gate) window = null; }

    public bool TryPair(string? code, out IssuedCredential? issued)
    {
        lock (gate)
        {
            issued = null;
            if (window is null || clock() >= window.ExpiresAt || failures >= 5) return false;
            var expected = System.Text.Encoding.ASCII.GetBytes(window.Code);
            var supplied = System.Text.Encoding.ASCII.GetBytes(code ?? "");
            if (!CryptographicOperations.FixedTimeEquals(expected, supplied))
            {
                failures++;
                return false;
            }
            var bytes = RandomNumberGenerator.GetBytes(32);
            var id = Guid.NewGuid().ToString();
            var entry = new StoredCredential(id, Convert.ToBase64String(SHA256.HashData(bytes)), clock());
            var replacement = new Dictionary<string, StoredCredential>(credentials) { [id] = entry };
            // A bounded credential set prevents unbounded growth from repeated pairing.
            if (replacement.Count > 16)
            {
                var oldest = replacement.Values.OrderBy(value => value.CreatedAt).First();
                replacement.Remove(oldest.Id);
            }
            Save(replacement);
            credentials = replacement;
            issued = new IssuedCredential(id, Convert.ToBase64String(bytes));
            CryptographicOperations.ZeroMemory(bytes);
            window = null; // Consume atomically after durable persistence, before replying.
            return true;
        }
    }

    public bool Authenticate(string? id, string? token)
    {
        lock (gate)
        {
            if (id is null || token is null || !credentials.TryGetValue(id, out var entry)) return false;
            try
            {
                var bytes = Convert.FromBase64String(token);
                if (bytes.Length != 32) return false;
                return CryptographicOperations.FixedTimeEquals(SHA256.HashData(bytes),
                    Convert.FromBase64String(entry.TokenHash));
            }
            catch (FormatException) { return false; }
        }
    }

    public void Revoke(string id)
    {
        lock (gate)
        {
            var replacement = new Dictionary<string, StoredCredential>(credentials);
            replacement.Remove(id);
            Save(replacement);
            credentials = replacement;
        }
    }
    public void RevokeAll()
    {
        lock (gate) { Save([]); credentials = []; window = null; }
    }
    private void Save(Dictionary<string, StoredCredential> replacement) =>
        LocalIdentity.AtomicWrite(path, ProtectedData.Protect(Protocol.Encode(replacement), null,
            DataProtectionScope.CurrentUser));
}
