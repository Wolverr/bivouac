# Developer setup

Bivouac builds on Linux. On Windows, use WSL2 (Linux inside Windows; near-native speed).

## Windows → WSL2 (one time)

1. **PowerShell as Administrator:**
   ```powershell
   wsl --install -d Ubuntu-24.04
   ```
   Reboot if asked, then open **Ubuntu** from the Start menu and create a Linux user.
2. **Check that systemd is on** (it is by default on new installs):
   ```bash
   grep -A1 '\[boot\]' /etc/wsl.conf    # should show systemd=true
   ```
   If not: `printf '[boot]\nsystemd=true\n' | sudo tee -a /etc/wsl.conf`, then in PowerShell `wsl --shutdown` and reopen Ubuntu.
3. **Keep the project inside the Linux filesystem** (`~/bivouac`), not under `/mnt/c/...`,
   which is many times slower for builds.
4. **Free space:** about 70 GB on the Windows drive (source data 15 GB, packs ~30 GB,
   toolchain + build ~10 GB, plus headroom). WSL's virtual disk grows as needed.

## Get the code and toolchain

```bash
sudo apt-get update && sudo apt-get install -y gh
gh auth login                                   # the repo is private until submission
gh repo clone Wolverr/bivouac ~/bivouac
cd ~/bivouac
tools/setup_dev.sh                              # JDK 17, Android SDK/NDK, CMake, Python env
source tools/dev.env
./gradlew assembleDebug                         # app/build/outputs/apk/debug/app-debug.apk
```

## Build the knowledge packs

```bash
tools/run_data_jobs.sh      # downloads FineWiki + pageviews + Overture places, builds both packs
tools/status.sh             # progress
```

The jobs run as systemd user services, so closing the terminal doesn't stop them.
**WSL caveat:** WSL can shut its Linux VM down a few seconds after the last WSL window
closes, which stops the jobs too. Keep one Ubuntu window open while they run, and set
Windows to not sleep while plugged in.

## Installing on a phone from WSL

USB devices aren't visible inside WSL by default. Easiest: use Windows' own adb.

1. Download *SDK Platform-Tools for Windows* from developer.android.com and unzip it.
2. In PowerShell:
   ```powershell
   .\adb.exe install -r \\wsl$\Ubuntu-24.04\home\<linux-user>\bivouac\app\build\outputs\apk\debug\app-debug.apk
   .\adb.exe push model.gguf /sdcard/Android/data/io.github.wolverr.bivouac/files/models/
   ```

(Alternative: `usbipd-win` attaches the phone to WSL so Linux adb sees it.)

## Picking up on the new machine

The project state lives in `docs/ROADMAP.md` (milestones, measurements, next steps),
so work can pick up where it last stopped.
