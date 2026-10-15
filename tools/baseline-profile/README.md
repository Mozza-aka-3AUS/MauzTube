# Baseline Profile e misure di fluidità

Script usati per generare `app/src/main/baseline-prof.txt` e misurare la fluidità
(emulatore Android con root, es. immagine `google_apis`, 1080×2400).

```bash
export ANDROID_HOME=~/Android/Sdk
# 1. Registrazione del profilo (build non offuscata "profiling")
./gradlew assembleProfiling
tools/baseline-profile/capture.sh app/build/outputs/apk/profiling/app-profiling.apk /tmp/raw-profile.txt
# poi copiare il contenuto in app/src/main/baseline-prof.txt (mantenendo l'intestazione)

# 2. Misura (avvio a freddo, scorrimento, navigazione) di un APK release
./gradlew assembleRelease
tools/baseline-profile/measure.sh app/build/outputs/apk/release/app-release.apk "etichetta"

# 3. Tempi per fase dei singoli fotogrammi
adb shell dumpsys gfxinfo com.mauztube framestats > /tmp/fs.txt && python3 tools/baseline-profile/frames.py /tmp/fs.txt
```
Rigenerare il profilo dopo modifiche importanti all'interfaccia o al player.
