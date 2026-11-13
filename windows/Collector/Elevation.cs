using System.ComponentModel;
using System.Diagnostics;
using System.Security.Principal;

namespace PcTelemetry;

internal static class Elevation
{
    public static bool IsAdministrator() => new WindowsPrincipal(WindowsIdentity.GetCurrent())
        .IsInRole(WindowsBuiltInRole.Administrator);

    // Elevate the interactive app once. Its sampling child inherits the token;
    // diagnostic CLI commands remain usable by ordinary users and test tools.
    public static int Restart(string[] args)
    {
        try
        {
            var start = new ProcessStartInfo(Environment.ProcessPath!)
            {
                UseShellExecute = true,
                Verb = "runas",
                WorkingDirectory = AppContext.BaseDirectory
            };
            // Also support framework-dependent launches through dotnet.exe.
            if (Path.GetFileNameWithoutExtension(start.FileName).Equals("dotnet", StringComparison.OrdinalIgnoreCase))
                start.ArgumentList.Add(typeof(Program).Assembly.Location);
            foreach (var argument in args) start.ArgumentList.Add(argument);
            using var process = Process.Start(start);
            return process is null ? 1 : 0;
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            // The user cancelled the Windows consent dialog. Do not retry.
            return 0;
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException)
        {
            MessageBox.Show("无法请求管理员权限：" + ex.Message, "PC Telemetry");
            return 1;
        }
    }
}
