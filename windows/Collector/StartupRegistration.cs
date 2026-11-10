using Microsoft.Win32;

namespace PcTelemetry;

public sealed class StartupRegistration(RegistryKey userRoot, string executable,
    string keyPath = @"Software\Microsoft\Windows\CurrentVersion\Run")
{
    public bool Enabled
    {
        get
        {
            using var key = userRoot.OpenSubKey(keyPath);
            return key?.GetValue("PcTelemetry") is string value && value == Command;
        }
    }
    public string Command => "\"" + Path.GetFullPath(executable) + "\" --tray";
    public void SetEnabled(bool enabled)
    {
        using var key = userRoot.CreateSubKey(keyPath);
        if (enabled) key.SetValue("PcTelemetry", Command);
        else key.DeleteValue("PcTelemetry", false);
    }
}
