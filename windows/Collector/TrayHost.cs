using Microsoft.Win32;

namespace PcTelemetry;

public static class TrayHost
{
    public static int Run(string[] args)
    {
        ApplicationConfiguration.Initialize();
        var options = Options.Parse(args) with { Lan = true };
        using var context = new CollectorTrayContext(options);
        Application.Run(context);
        return 0;
    }
}

internal sealed class CollectorTrayContext : ApplicationContext
{
    private readonly CollectorRuntime runtime;
    private readonly CancellationTokenSource cancellation = new();
    private readonly NotifyIcon icon;
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    private readonly ToolStripMenuItem status = new("正在启动");
    private readonly Task execution;
    private Form? pairingForm;

    public CollectorTrayContext(Options options)
    {
        runtime = new CollectorRuntime(options);
        var menu = new ContextMenuStrip();
        status.Enabled = false;
        menu.Items.Add(status);
        menu.Items.Add("打开配对信息", null, (_, _) => ShowPairing(options.Port));
        var startup = new ToolStripMenuItem("开机自启") { CheckOnClick = true, Checked = StartupEnabled() };
        startup.CheckedChanged += (_, _) =>
        {
            try { SetStartup(startup.Checked); }
            catch (Exception ex) when (ex is UnauthorizedAccessException or IOException)
            { MessageBox.Show("无法修改当前用户的开机自启设置。", "PC Telemetry"); }
        };
        menu.Items.Add(startup);
        menu.Items.Add("移除全部配对", null, (_, _) =>
        {
            if (MessageBox.Show("移除所有手机的配对凭据？", "PC Telemetry",
                MessageBoxButtons.YesNo) == DialogResult.Yes) runtime.Pairing.RevokeAll();
        });
        menu.Items.Add("退出", null, (_, _) => ExitThread());
        icon = new NotifyIcon { Icon = SystemIcons.Application, Text = "PC Telemetry", Visible = true,
            ContextMenuStrip = menu };
        icon.DoubleClick += (_, _) => ShowPairing(options.Port);
        execution = Task.Run(() => runtime.RunAsync(cancellation.Token));
        timer.Tick += (_, _) =>
        {
            status.Text = execution.IsFaulted ? "采集服务异常，请退出后重启" :
                $"连接数 {runtime.Telemetry.ConnectionCount} · " +
                (runtime.Addresses.Count > 0 ? "私有局域网" : "仅本机，请检查网络配置文件");
            icon.Text = execution.IsFaulted ? "PC Telemetry · 服务异常" :
                $"PC Telemetry · {runtime.Telemetry.ConnectionCount} connected";
        };
        timer.Start();
    }

    private void ShowPairing(int port)
    {
        if (pairingForm is not null) { pairingForm.Activate(); return; }
        var window = runtime.Pairing.OpenWindow();
        var form = new Form { Text = "PC Telemetry · 首次配对", Width = 700, Height = 420,
            StartPosition = FormStartPosition.CenterScreen, MaximizeBox = false };
        var content = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(20),
            ColumnCount = 1, RowCount = 6 };
        content.Controls.Add(new Label { Text = "在手机上核对下方完整证书指纹，再输入一次性配对码。",
            AutoSize = true });
        content.Controls.Add(new TextBox { Text = runtime.Identity.Fingerprint, ReadOnly = true,
            Dock = DockStyle.Fill, Multiline = true, Height = 55 });
        content.Controls.Add(new Label { Text = window.Code, Font = new Font(SystemFonts.DefaultFont.FontFamily, 30),
            AutoSize = true });
        var remaining = new Label { AutoSize = true };
        content.Controls.Add(remaining);
        var endpoints = runtime.Addresses.Count == 0 ? "无私有网络地址，当前仅监听127.0.0.1。" :
            string.Join(Environment.NewLine, runtime.Addresses.Select(address => $"wss://{address}:{port}/v1/stream"));
        content.Controls.Add(new TextBox { Text = endpoints, ReadOnly = true, Multiline = true,
            Dock = DockStyle.Fill, Height = 60 });
        content.Controls.Add(new Label { Text = "手机与电脑应处于同一私有局域网。更改网络配置后请重启Collector。",
            AutoSize = true });
        form.Controls.Add(content);
        var countdown = new System.Windows.Forms.Timer { Interval = 1000 };
        countdown.Tick += (_, _) => remaining.Text = runtime.Pairing.WindowActive
            ? $"有效期剩余 {Math.Max(0, (int)(window.ExpiresAt - DateTimeOffset.UtcNow).TotalSeconds)} 秒"
            : "配对码已使用、过期或失败次数达到上限。关闭后重新打开可获取新码。";
        countdown.Start();
        form.FormClosed += (_, _) =>
        {
            countdown.Dispose(); runtime.Pairing.CloseWindow(); pairingForm = null;
        };
        pairingForm = form;
        form.Show();
    }

    private static bool StartupEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        return key?.GetValue("PcTelemetry") is string;
    }
    private static void SetStartup(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if (enabled)
        {
            var executable = Path.Combine(AppContext.BaseDirectory, "Collector.exe");
            key.SetValue("PcTelemetry", "\"" + executable + "\" --tray");
        }
        else key.DeleteValue("PcTelemetry", false);
    }

    protected override void ExitThreadCore()
    {
        timer.Stop();
        pairingForm?.Close();
        cancellation.Cancel();
        try { execution.GetAwaiter().GetResult(); }
        catch (Exception ex) when (ex is OperationCanceledException or IOException or InvalidOperationException) { }
        runtime.DisposeAsync().AsTask().GetAwaiter().GetResult();
        icon.Visible = false;
        icon.Dispose();
        timer.Dispose();
        cancellation.Dispose();
        base.ExitThreadCore();
    }
}
