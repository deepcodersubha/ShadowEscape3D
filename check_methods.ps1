Add-Type -AssemblyName System.IO.Compression.FileSystem

$tempJar = "C:\Users\dell\.gemini\antigravity\brain\23759bcc-de1f-47b3-a170-1fb941a40ea6\scratch\filament_classes.jar"
$zip = [System.IO.Compression.ZipFile]::OpenRead($tempJar)

# We can search javap or check methods if javap is available
$zip.Dispose()
Write-Host "Jar accessible"
