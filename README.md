# XML Compare

JavaFX desktop app: compare two XML files and browse a structural diff (tree, filters, export, copy subtrees).

**Needs:** JDK 21 and Maven on your PATH.

## Get the `.exe` (Windows)

From the repo folder:

```powershell
.\scripts\run.ps1 -BuildExe
```

That runs `mvn -Pdist clean package` and opens File Explorer on **`target\dist\XmlCompare\XmlCompare.exe`**. Keep the whole **`XmlCompare`** folder together (bundled runtime). The `.exe` uses the app icon from `src\main\resources\packaging\app.ico`.

## Run without building an `.exe`

```powershell
mvn clean package
.\scripts\run.ps1
```

Or during development:

```bash
mvn javafx:run
```

On Linux/macOS, after `mvn clean package`: `./scripts/run.sh`

## Changelog

[CHANGELOG.md](CHANGELOG.md)
