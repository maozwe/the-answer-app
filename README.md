# חיפוש חוזים (Android ו-Windows)

אפליקציות שמציגות את דף החיפוש בחוזים ובמסמכי הפרויקט (web.py בשרת, דרך Tailscale),
מעתיקות את השאלה והסעיפים ל-Claude, ומאפשרות להוסיף קבצים לפרויקט וקבצים זמניים לשיחה.
הכניסה בשם משתמש וסיסמה של manage-control; חשבון נפתח באתר weisscivitech.com.

- `app/`: אפליקציית Android (WebView).
- `windows/`: `TheAnswer.exe` ל-Windows (WinForms + WebView2, קובץ יחיד).

**התקנה ועדכון:** ההורדה מהחנות ב-weisscivitech.com. האפליקציות בודקות גרסה חדשה בפתיחה
ובכפתור "בדוק עדכונים", ומתקינות רק קובץ שה-sha256 שלו תואם לזה שבשרת.

כל merge ל-`main` בונה את שתיהן ומפרסם כל אחת בקו גרסאות משלה ב-manage-control
(`.github/workflows/release.yml`, `scripts/mc.sh`, סוד `MANAGE_CONTROL_API_KEY` מסוג RELEASE).
הקוד כאן הוא האפליקציות בלבד: אין בו מסמכים, אינדקס או סיסמאות.

Build locally: JDK 17 + Android SDK 36, `VERSION_NAME=1.0.0 ./gradlew assembleDebug`;
.NET 8 SDK, `dotnet publish windows/TheAnswer.csproj -c Release -p:Version=1.0.0 -o out`.

**First install over a test build:** Android rejects an update signed with a different key, so uninstall a test (debug) build once before installing a release.
