using System.Diagnostics;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace TheAnswer;

// The search page (web.py on the server, over Tailscale) in a window, with the same bridge the
// Android app gives it: clipboard, opening Claude in the browser, version and update check.
static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

sealed class MainForm : Form
{
    const string DefaultUrl = "http://100.71.25.67:8765/";
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TheAnswer");
    static readonly string SettingsFile = Path.Combine(Dir, "server.txt");

    readonly WebView2 web = new() { Dock = DockStyle.Fill };
    bool errorShown;
    int retries;
    CoreWebView2Environment? env;
    ClaudeWindow? claude;
    string lastCopied = "";  // what "שאל את Claude" copied: the Claude window pastes it

    public MainForm()
    {
        Text = "התשובה - חיפוש בחוזה";
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;
        Width = 1100; Height = 850;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!);
        Controls.Add(web);
        Load += async (_, _) => await Start();
    }

    static string ServerUrl() => File.Exists(SettingsFile) ? File.ReadAllText(SettingsFile).Trim() : DefaultUrl;

    async Task Start()
    {
        try
        {
            env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Dir, "WebView2"));
        }
        catch (WebView2RuntimeNotFoundException)
        {
            Dialogs.Say(this, "חסר רכיב Microsoft Edge WebView2 Runtime. התקן אותו מאתר Microsoft והפעל שוב.");
            OpenExternal("https://developer.microsoft.com/microsoft-edge/webview2/");
            Close();
            return;
        }
        await web.EnsureCoreWebView2Async(env);
        var core = web.CoreWebView2;
        core.Settings.AreDevToolsEnabled = false;
        await core.AddScriptToExecuteOnDocumentCreatedAsync(
            "window.DesktopApp = {" +
            " copy: (t) => chrome.webview.postMessage({ op: 'copy', text: String(t) })," +
            " openExternal: (u) => chrome.webview.postMessage({ op: 'open', url: String(u) })," +
            " checkUpdate: () => chrome.webview.postMessage({ op: 'update' })," +
            " settings: () => chrome.webview.postMessage({ op: 'settings' })," +
            " pastesIntoClaude: true," +
            $" version: () => {JsonSerializer.Serialize(Updater.Current)} }};");
        core.WebMessageReceived += (_, e) => OnMessage(e.WebMessageAsJson);
        core.NewWindowRequested += (_, e) => { e.Handled = true; OpenExternal(e.Uri); };  // claude.ai and links: the browser
        core.NavigationStarting += (_, e) =>
        {
            if (Uri.TryCreate(e.Uri, UriKind.Absolute, out var u) && u.Scheme.StartsWith("http")
                && u.Host != new Uri(ServerUrl()).Host) { e.Cancel = true; OpenExternal(e.Uri); }
        };
        core.NavigationCompleted += async (_, e) =>
        {
            if (e.IsSuccess) { errorShown = false; retries = 0; return; }
            if (e.WebErrorStatus == CoreWebView2WebErrorStatus.OperationCanceled) return;
            if (retries++ < 2)  // the server restarting (an update) drops the connection for a few seconds
            {
                await Task.Delay(3000);
                core.Navigate(ServerUrl());
                return;
            }
            retries = 0;
            ConnectionError(e.WebErrorStatus.ToString());
        };
        core.Navigate(ServerUrl());
        _ = Updater.Check(this, quiet: true);
    }

    void OnMessage(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var m = doc.RootElement;
        switch (m.GetProperty("op").GetString())
        {
            case "copy":
                lastCopied = m.GetProperty("text").GetString() ?? "";
                Clipboard.SetText(lastCopied);
                break;
            case "open":
                var url = m.GetProperty("url").GetString() ?? "";
                if (url.StartsWith("https://claude.ai/") && env != null && lastCopied.Length > 0)
                {
                    claude ??= new ClaudeWindow(env);
                    claude.Ask(lastCopied);
                }
                else OpenExternal(url);
                break;
            case "update": _ = Updater.Check(this, quiet: false); break;
            case "settings": AskServerUrl(); break;
        }
    }

    static void OpenExternal(string url)
    {
        if (!url.StartsWith("https://")) return;  // only web links leave the app
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }

    void ConnectionError(string detail)
    {
        if (errorShown) return;
        errorShown = true;
        web.CoreWebView2.NavigateToString("<html dir='rtl'><body style='font-family:sans-serif;padding:24px'>"
            + "<h3>אין חיבור לשרת החיפוש</h3><p>בדוק ש-Tailscale מחובר ושהשרת דולק.</p></body></html>");
        if (Dialogs.Ask(this, $"אין חיבור לשרת.\nכתובת: {ServerUrl()}\n{detail}\n\nבדוק ש-Tailscale מחובר.\n\nלנסות שוב? (לא = שינוי כתובת)") == DialogResult.Yes)
        {
            errorShown = false;
            web.CoreWebView2.Navigate(ServerUrl());
        }
        else AskServerUrl();
    }

    void AskServerUrl()
    {
        using var form = new Form { Text = "כתובת שרת החיפוש", ClientSize = new Size(440, 84), FormBorderStyle = FormBorderStyle.FixedDialog,
                                    StartPosition = FormStartPosition.CenterParent, MinimizeBox = false, MaximizeBox = false,
                                    RightToLeft = RightToLeft.Yes, RightToLeftLayout = true };
        var input = new TextBox { Text = ServerUrl(), Left = 10, Top = 12, Width = 420, RightToLeft = RightToLeft.No };
        var ok = new Button { Text = "שמור", DialogResult = DialogResult.OK, Left = 10, Top = 46, Width = 90 };
        form.Controls.AddRange([input, ok]);
        form.AcceptButton = ok;
        if (form.ShowDialog(this) == DialogResult.OK && Uri.TryCreate(input.Text.Trim(), UriKind.Absolute, out var u) && u.Scheme.StartsWith("http"))
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(SettingsFile, u.ToString());
        }
        errorShown = false;
        web.CoreWebView2.Navigate(ServerUrl());
    }
}

static class Dialogs
{
    const MessageBoxOptions Rtl = MessageBoxOptions.RightAlign | MessageBoxOptions.RtlReading;

    public static void Say(IWin32Window owner, string text) =>
        MessageBox.Show(owner, text, "התשובה", MessageBoxButtons.OK, MessageBoxIcon.Information, MessageBoxDefaultButton.Button1, Rtl);

    public static DialogResult Ask(IWin32Window owner, string text) =>
        MessageBox.Show(owner, text, "התשובה", MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button1, Rtl);
}
