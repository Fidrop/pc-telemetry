using System.Diagnostics;

namespace PcTelemetry;

internal sealed class DriverSetupDialog : Form
{
    private readonly Func<PawnIoStatus> readStatus;
    private readonly Func<IProgress<DriverInstallProgress>, CancellationToken, Task<DriverInstallResult>> install;
    private readonly Label heading = new() { AutoSize = true };
    private readonly Label description = new() { AutoSize = true, MaximumSize = new Size(530, 0) };
    private readonly Label state = new() { AutoSize = true, MaximumSize = new Size(530, 0) };
    private readonly ProgressBar progress = new() { Dock = DockStyle.Top, Visible = false };
    private readonly Button installButton = new() { Text = "安装并继续", AutoSize = true };
    private readonly Button laterButton = new() { Text = "暂不安装", AutoSize = true };
    private readonly CancellationTokenSource cancellation = new();
    private bool busy;
    private bool installing;

    public DriverSetupDialog() : this(PawnIoStatus.Read, InstallOfficialAsync) { }
    internal DriverSetupDialog(Func<PawnIoStatus> readStatus,
        Func<IProgress<DriverInstallProgress>, CancellationToken, Task<DriverInstallResult>> install)
    {
        this.readStatus = readStatus; this.install = install;
        Text = "PC Telemetry · 温度采集";
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(590, 440);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false; MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        var content = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(24),
            ColumnCount = 1, RowCount = 6 };
        content.RowStyles.Add(new(SizeType.AutoSize));
        content.RowStyles.Add(new(SizeType.AutoSize));
        content.RowStyles.Add(new(SizeType.AutoSize));
        content.RowStyles.Add(new(SizeType.Absolute, 26));
        content.RowStyles.Add(new(SizeType.Percent, 100));
        content.RowStyles.Add(new(SizeType.AutoSize));
        heading.Font = new Font((SystemFonts.MessageBoxFont ?? SystemFonts.DefaultFont).FontFamily, 16, FontStyle.Bold);
        heading.Margin = new Padding(0, 0, 0, 16);
        description.Margin = new Padding(0, 0, 0, 16);
        state.Margin = new Padding(0, 10, 0, 12);
        var links = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill };
        var official = new LinkLabel { Text = "驱动官网", AutoSize = true, Margin = new Padding(0, 0, 16, 12) };
        var license = new LinkLabel { Text = "驱动许可（GPL-2.0）", AutoSize = true };
        official.LinkClicked += (_, _) => OpenLink("https://pawnio.eu/");
        license.LinkClicked += (_, _) => OpenLink("https://github.com/namazso/PawnIO/blob/master/COPYING");
        links.Controls.Add(official); links.Controls.Add(license);
        var buttons = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft };
        buttons.Controls.Add(laterButton); buttons.Controls.Add(installButton);
        installButton.Click += async (_, _) => await BeginInstallAsync();
        laterButton.Click += (_, _) => { if (busy) cancellation.Cancel(); else Close(); };
        content.Controls.Add(heading, 0, 0); content.Controls.Add(description, 0, 1); content.Controls.Add(links, 0, 2);
        content.Controls.Add(progress, 0, 3); content.Controls.Add(state, 0, 4); content.Controls.Add(buttons, 0, 5);
        Controls.Add(content);
        RefreshStatus();
        FormClosing += (_, args) =>
        {
            if (!busy) return;
            args.Cancel = true;
            if (!installing) cancellation.Cancel();
        };
    }

    private void RefreshStatus()
    {
        var status = readStatus();
        if (status.Ready)
        {
            heading.Text = "温度采集驱动已就绪";
            description.Text = $"已检测到可用的 PawnIO {status.Version}。本次启动将直接使用已安装的驱动采集温度。";
            installButton.Visible = false; laterButton.Text = "关闭";
        }
        else
        {
            heading.Text = "开启 CPU 温度采集";
            description.Text = "读取 CPU 温度需要在 Windows 中安装 PawnIO 采集驱动。\n\n" +
                "点击安装将联网下载官方 PawnIO 2.2.0 安装包，校验来源和数字签名后安装。完成后自动开始温度采集。\n\n" +
                "也可暂不安装，继续查看使用率和内存；以后从电脑托盘的“温度采集驱动”补装。";
            state.Text = status.Version is null ? "尚未安装温度采集驱动。" :
                $"检测到 PawnIO {status.Version}，但当前不可用。可尝试修复安装。";
        }
    }

    private async Task BeginInstallAsync()
    {
        busy = true; installButton.Enabled = false; progress.Visible = true;
        laterButton.Text = "取消下载";
        try
        {
            var reporter = new Progress<DriverInstallProgress>(update =>
            {
                state.Text = update.Message;
                installing = update.Installing;
                laterButton.Enabled = !installing;
                progress.Style = update.Percent.HasValue ? ProgressBarStyle.Continuous : ProgressBarStyle.Marquee;
                if (update.Percent is { } value) progress.Value = Math.Clamp(value, 0, 100);
            });
            var result = await install(reporter, cancellation.Token);
            if (result == DriverInstallResult.RestartWindowsRequired)
            {
                MessageBox.Show(this, "驱动安装程序提示需要重启 Windows。请保存工作后自行重启电脑；当前可继续查看使用率和内存。",
                    "PC Telemetry", MessageBoxButtons.OK, MessageBoxIcon.Information);
                busy = false; DialogResult = DialogResult.Retry;
            }
            else { busy = false; DialogResult = DialogResult.OK; }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { busy = false; Close(); }
        catch (OperationCanceledException)
        {
            busy = false; state.Text = "下载超时，请检查网络后重试。";
            installButton.Enabled = true; laterButton.Enabled = true;
            laterButton.Text = "暂不安装"; progress.Visible = false;
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or InvalidDataException or UnauthorizedAccessException or
            System.ComponentModel.Win32Exception or System.Security.SecurityException)
        {
            busy = false; installing = false;
            state.Text = "安装未完成：" + (ex is HttpRequestException ? "无法下载官方安装包，请检查网络后重试。" : ex.Message);
            installButton.Enabled = true; laterButton.Enabled = true; laterButton.Text = "暂不安装";
            progress.Visible = false;
        }
    }

    private static async Task<DriverInstallResult> InstallOfficialAsync(IProgress<DriverInstallProgress> progress,
        CancellationToken cancellation)
    {
        using var installer = new PawnIoInstaller();
        return await installer.InstallAsync(progress, cancellation);
    }

    private void OpenLink(string url)
    {
        try { using var process = Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (System.ComponentModel.Win32Exception) { state.Text = "请使用浏览器访问：" + url; }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) cancellation.Dispose();
        base.Dispose(disposing);
    }
}
