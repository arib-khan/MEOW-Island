# Removes the old custom-screen headers from the firmware folder. Run from the project folder that contains firmware\ (for example C:\Users\hp\Desktop\smart-island):
#   powershell -ExecutionPolicy Bypass -File .\REMOVE-custom-screen-firmware.ps1
$files = @('firmware\MeowIsland\custom_store.h', 'firmware\MeowIsland\mani.h', 'firmware\MeowIsland\mimg.h', 'tests\mimg_vectors.txt', 'tests\mani_vectors.txt',
           'firmware\host-tests\mani_test.cpp', 'firmware\host-tests\custom_store_test.cpp')
foreach ($f in $files) { if (Test-Path $f) { Remove-Item $f -Force; Write-Host "removed $f" } }
# the music screen only needs its include renamed
$m = 'firmware\MeowIsland\music_screen.h'
if (Test-Path $m) { $t = Get-Content $m -Raw; if ($t -match 'custom_store\.h') { ($t -replace 'custom_store\.h', 'photo_store.h') | Set-Content $m -NoNewline; Write-Host "updated include in $m" } }
Write-Host 'Done. Open firmware\MeowIsland\MeowIsland.ino in the Arduino IDE.'
