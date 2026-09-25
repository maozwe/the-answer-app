using System.Diagnostics;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace TheAnswer;

// claude.ai in a window of its own, with the question and sections pasted into the message box: the
// search page's "שאל את Claude" copies them, and this window pastes them once the box has loaded
// (after a sign-in too). Sending stays with the user (Enter). Sign-in is kept, as the environment is
// shared with the main window.
sealed class ClaudeWindow : Form
{
    const string NewChat = "https://claude.ai/new";
    readonly WebView2 web = new() { Dock = DockStyle.Fill };
    readonly CoreWebView2Environment env;
    string? pending;  // text still to paste

    public ClaudeWindow(CoreWebView2Environment env)
    {
        this.env = env;
        Text = "Claude";
        Width = 1000; Height = 850;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!);
        Controls.Add(web);
        Load += async (_, _) => await Start();
    }

    async Task Start()
    {
        await web.EnsureCoreWebView2Async(env);
        var core = web.CoreWebView2;
        core.NewWindowRequested += (_, e) =>  // links in answers: the browser
        {
            e.Handled = true;
            if (e.Uri.StartsWith("https://")) Process.Start(new ProcessStartInfo(e.Uri) { UseShellExecute = true });
        };
        core.NavigationCompleted += async (_, e) => { if (e.IsSuccess) await PasteIfReady(); };
        if (pending != null) core.Navigate(NewChat);
    }

    /// <summary>Opens (or brings back) the window on a new chat with this text to paste.</summary>
    public void Ask(string text)
    {
        pending = text;
        if (web.CoreWebView2 != null) web.CoreWebView2.Navigate(NewChat);  // else Start() navigates
        if (!Visible) Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }

    async Task PasteIfReady()
    {
        if (pending == null || !new Uri(web.Source.ToString()).Host.EndsWith("claude.ai")) return;
        // Waits up to 20 s for the message box, then pastes as a real paste would (a long text becomes
        // an attachment, as when the user presses Ctrl+V); insertText if the paste was not taken.
        var script = """
            (async (text) => {
              for (let i = 0; i < 40; i++) {
                const box = document.querySelector('div.ProseMirror[contenteditable="true"]') || document.querySelector('[contenteditable="true"]');
                if (box) {
                  box.focus();
                  const dt = new DataTransfer();
                  dt.setData("text/plain", text);
                  box.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
                  await new Promise(r => setTimeout(r, 1500));
                  const taken = box.innerText.trim().length > 0 || document.querySelector('[data-testid*="file"], [data-testid*="attachment"]');
                  if (!taken) document.execCommand("insertText", false, text);
                  return;
                }
                await new Promise(r => setTimeout(r, 500));
              }
            })
            """ + "(" + JsonSerializer.Serialize(pending) + ");";
        var box = await web.CoreWebView2.ExecuteScriptAsync(
            "!!(document.querySelector('div.ProseMirror[contenteditable=\"true\"]') || document.querySelector('[contenteditable=\"true\"]') || location.pathname.startsWith('/new'))");
        if (box != "true") return;  // the sign-in page: paste after the user signs in
        pending = null;
        await web.CoreWebView2.ExecuteScriptAsync(script);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }  // kept for the next question
        base.OnFormClosing(e);
    }
}
