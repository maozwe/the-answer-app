using System.Diagnostics;
using System.Net.Http.Json;
using System.Reflection;
using System.Security.Cryptography;

namespace TheAnswer;

// Newest published Windows version from manage-control (public, no key). Replaces nothing unless the
// downloaded file hashes to the sha256 the backend stored for it.
static class Updater
{
    public const string Slug = "the-answer";  // the app's slug in manage-control
    const string Latest = "https://api.weisscivitech.com/api/v1/storefront/apps/" + Slug + "/latest?platform=windows";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(5) };

    public static string Current =>
        (Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.0.0").Split('+')[0];

    record Download(string url, string filename, long sizeBytes, string sha256);
    record LatestRelease(string version, Download? download);

    public static async Task Check(IWin32Window owner, bool quiet)
    {
        try
        {
            var latest = await Http.GetFromJsonAsync<LatestRelease>(Latest);
            if (latest?.download is null || !Version.TryParse(latest.version, out var v) || v <= Version.Parse(Current))
            {
                if (!quiet) Dialogs.Say(owner, $"הגרסה עדכנית ({Current}).");
                return;
            }
            if (Dialogs.Ask(owner, $"גרסה {latest.version} זמינה (מותקנת {Current}). לעדכן עכשיו?") != DialogResult.Yes) return;
            var bytes = await Http.GetByteArrayAsync(latest.download.url);
            if (!Convert.ToHexString(SHA256.HashData(bytes)).Equals(latest.download.sha256, StringComparison.OrdinalIgnoreCase))
            {
                Dialogs.Say(owner, "הקובץ שהורד לא תואם לחתימה שבשרת. העדכון בוטל.");
                return;
            }
            var exe = Environment.ProcessPath!;
            var fresh = exe + ".new";
            await File.WriteAllBytesAsync(fresh, bytes);
            // swap once this process has exited, then start the new version
            static string Q(string s) => "'" + s.Replace("'", "''") + "'";
            Process.Start(new ProcessStartInfo("powershell.exe",
                $"-NoProfile -WindowStyle Hidden -Command \"Wait-Process -Id {Environment.ProcessId} -ErrorAction SilentlyContinue; " +
                $"Move-Item -Force -LiteralPath {Q(fresh)} -Destination {Q(exe)}; Start-Process -FilePath {Q(exe)}\"")
                { UseShellExecute = false, CreateNoWindow = true });
            Application.Exit();
        }
        catch (Exception e)
        {
            if (!quiet) Dialogs.Say(owner, "בדיקת העדכון נכשלה: " + e.Message);
        }
    }
}
