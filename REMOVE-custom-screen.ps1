# Removes the custom HTML screen feature from the app project. Run from C:\si:   powershell -ExecutionPolicy Bypass -File .\REMOVE-custom-screen.ps1
$ErrorActionPreference = 'Continue'
$files = @(
  'plugins\kotlin\CustomRender.kt', 'plugins\kotlin\CustomScreen.kt',
  'src\screens\CustomScreen.tsx', 'src\core\customscreen.ts', 'src\core\animscreen.ts',
  'tests\animscreen.test.js', 'tests\customscreen.test.js', 'tests\mani_vectors.txt', 'tests\mimg_vectors.txt',
  'docs\CUSTOM_SCREENS.md'
)
$dirs = @('tests\browser', 'examples\custom-screen')
foreach ($f in $files) { if (Test-Path $f) { Remove-Item $f -Force; Write-Host "removed $f" } }
foreach ($d in $dirs) { if (Test-Path $d) { Remove-Item $d -Recurse -Force; Write-Host "removed $d" } }
if ((Test-Path 'examples') -and -not (Get-ChildItem 'examples' -Force)) { Remove-Item 'examples' -Force }
Write-Host 'Done. Next: npm test'
