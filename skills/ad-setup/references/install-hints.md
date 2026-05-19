# Install hints — JDK + adb per platform

Read this from `/android-debugger:ad-setup` step 3 when `adb_status: "not_found"` (or JDK is missing/too old). Platform-specific commands and env-var hints kept out of the skill body so URLs and cask names can be updated without rewriting the body.

## JDK (minimum: JDK 17)

### macOS

- Homebrew: `brew install --cask temurin@21` (Eclipse Temurin 21 LTS) or `brew install openjdk@21`.
- Manual: download from `https://adoptium.net/temurin/releases/?version=21`.
- Verify: `java -version` (should print 17 or higher).
- If `java` is on `PATH` but `JAVA_HOME` isn't set: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

### Linux

- Debian/Ubuntu: `sudo apt install openjdk-21-jdk` (or `openjdk-17-jdk`).
- Fedora: `sudo dnf install java-21-openjdk-devel`.
- Arch: `sudo pacman -S jdk21-openjdk`.
- Verify: `java -version`.
- `JAVA_HOME` example: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

### Windows

- Manual: download Temurin 21 MSI from `https://adoptium.net/temurin/releases/?version=21&os=windows`.
- Chocolatey: `choco install temurin21`.
- Scoop: `scoop install temurin21-jdk`.
- Verify: `java -version` in PowerShell or cmd.exe.
- `JAVA_HOME`: System Properties → Environment Variables → System variables → New → `JAVA_HOME` = `C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot`.

## adb (Android SDK platform-tools)

### macOS

- Homebrew: `brew install --cask android-platform-tools`.
- Bundled with Android Studio: install Studio, then platform-tools is at `~/Library/Android/sdk/platform-tools/`.
- Set `ANDROID_HOME=$HOME/Library/Android/sdk` so the server's `AdbLocator` finds it.
- Or set `ADB_PATH=/full/path/to/adb` to override.

### Linux

- Debian/Ubuntu: `sudo apt install android-tools-adb` (older platform-tools — works for most plugin needs).
- Standalone latest: download `https://dl.google.com/android/repository/platform-tools-latest-linux.zip`, extract, add `platform-tools/` to `PATH`.
- With Android Studio installed: `export ANDROID_HOME=$HOME/Android/Sdk`.

### Windows

- Standalone: download `https://dl.google.com/android/repository/platform-tools-latest-windows.zip`, extract to `C:\platform-tools\`, add to `PATH`.
- With Android Studio: platform-tools is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\`. Set `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`.
- Chocolatey: `choco install adb`.
- Verify: `adb version` in PowerShell or cmd.exe.

## Resolution order the server uses

The Kotlin `AdbLocator` resolves `adb` in this order; report this back to the user verbatim so they know what to set:

1. `ADB_PATH` env var (highest priority — bypass everything else).
2. `ANDROID_HOME/platform-tools/adb` (most users with Android Studio installed).
3. `ANDROID_SDK_ROOT/platform-tools/adb` (deprecated env name; still honored).
4. `adb` on `PATH`.

If `adb_status: "not_found"`, the server has tried all four and reports the locations it checked. Surface that list to the user.
