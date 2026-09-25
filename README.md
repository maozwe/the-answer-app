# חיפוש חוזים (Android)

אפליקציה שמציגה את דף החיפוש בחוזים ובמסמכי הפרויקט (web.py בשרת, דרך Tailscale),
מעתיקה את השאלה והסעיפים ל-Claude, ומאפשרת להוסיף קבצים מהטלפון לכל פרויקט.

**התקנה:** מורידים את קובץ ה-APK מהגרסה האחרונה ב-[Releases](../../releases/latest) ומתקינים.
**עדכון:** באפליקציה, "בדוק עדכונים" (או אוטומטית בפתיחה).

כל merge ל-`main` בונה APK חתום ומפרסם גרסה חדשה (`.github/workflows/release.yml`).
הקוד כאן הוא האפליקציה בלבד: אין בו מסמכים, אינדקס או סיסמאות. כתובת השרת היא כתובת Tailscale,
נגישה רק למכשירים של בעל החשבון.

Build locally: JDK 17, Android SDK 36, `./gradlew assembleDebug`.

**First install over a test build:** Android rejects an update signed with a different key, so uninstall a test (debug) build once before installing a release.
