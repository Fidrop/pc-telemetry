using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Security.AccessControl;
using System.Security.Principal;

namespace PcTelemetry;

public sealed record LocalSecrets(string DeviceId, string CredentialId, string Token, byte[] Certificate);

public sealed class LocalIdentity : IDisposable
{
    public LocalSecrets Secrets { get; }
    public X509Certificate2 Certificate { get; }
    public string Fingerprint => Convert.ToHexString(SHA256.HashData(Certificate.RawData));

    public LocalIdentity(string directory)
    {
        Directory.CreateDirectory(directory);
        var secretsDirectory = Path.Combine(directory, "secrets");
        Directory.CreateDirectory(secretsDirectory);
        var permissions = new DirectorySecurity();
        permissions.SetAccessRuleProtection(true, false);
        permissions.AddAccessRule(new FileSystemAccessRule(
            WindowsIdentity.GetCurrent().User ?? throw new InvalidOperationException("Missing Windows identity"),
            FileSystemRights.FullControl, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(secretsDirectory).SetAccessControl(permissions);
        var path = Path.Combine(secretsDirectory, "identity.dpapi");
        var legacyPath = Path.Combine(directory, "identity.dpapi");
        if (!File.Exists(path) && File.Exists(legacyPath))
            File.Move(legacyPath, path);
        if (File.Exists(path))
        {
            var plaintext = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
            try { Secrets = Protocol.Decode<LocalSecrets>(System.Text.Encoding.UTF8.GetString(plaintext)); }
            finally { CryptographicOperations.ZeroMemory(plaintext); }
        }
        else
        {
            using var key = RSA.Create(2048);
            var request = new CertificateRequest("CN=PC Telemetry local development", key,
                HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
            var names = new SubjectAlternativeNameBuilder();
            names.AddIpAddress(System.Net.IPAddress.Loopback);
            names.AddDnsName("localhost");
            request.CertificateExtensions.Add(names.Build());
            using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5),
                DateTimeOffset.UtcNow.AddYears(1));
            Secrets = new LocalSecrets(Guid.NewGuid().ToString(), Guid.NewGuid().ToString(),
                Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
                certificate.Export(X509ContentType.Pfx));
            var plaintext = Protocol.Encode(Secrets);
            try { AtomicWrite(path, ProtectedData.Protect(plaintext, null, DataProtectionScope.CurrentUser)); }
            finally { CryptographicOperations.ZeroMemory(plaintext); }
        }
        Certificate = new X509Certificate2(Secrets.Certificate, (string?)null,
            X509KeyStorageFlags.DefaultKeySet);
        if (Certificate.NotAfter.ToUniversalTime() <= DateTime.UtcNow)
            throw new InvalidOperationException("Local TLS certificate expired; use a fresh development data directory.");
    }

    public bool Authenticate(string? credentialId, string? token)
    {
        if (credentialId != Secrets.CredentialId || token is null) return false;
        try
        {
            return CryptographicOperations.FixedTimeEquals(Convert.FromBase64String(token),
                Convert.FromBase64String(Secrets.Token));
        }
        catch (FormatException) { return false; }
    }

    public static void AtomicWrite(string path, byte[] bytes)
    {
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, bytes);
            File.Move(temporary, path, true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    public void Dispose() => Certificate.Dispose();
}
