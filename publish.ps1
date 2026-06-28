# publish.ps1 - one-step publisher for AppBlocker.
# You don't run this directly: just double-click Publish.bat. It bumps the version,
# builds the signed app, and uploads it to GitHub so your phone can update itself.
# (Advanced: pass -Note "what changed" to skip the prompt and run unattended.)

param([string]$Note)

$ErrorActionPreference = 'Stop'

# --- Config (these rarely change) -------------------------------------------------
$Repo        = $PSScriptRoot
$Owner       = 'boudiahdab2003-art'
$RepoName    = 'AppBlocker'
$Jbr         = 'C:\Program Files\Android\Android Studio\jbr'
$Sdk         = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$GradleBat   = Join-Path $Repo '_tools\gradle\gradle-8.9\bin\gradle.bat'
$GradleFile  = Join-Path $Repo 'app\build.gradle.kts'
$ApkBuilt    = Join-Path $Repo 'app\build\outputs\apk\release\app-release.apk'
$ApkAsset    = Join-Path $Repo 'AppBlocker.apk'
$Changelog   = Join-Path $Repo 'CHANGELOG.md'
$ReleaseCert = '027953856e2a387cb2618b3b2e2cd35e119e3416d6b551aeff3cb9d09148b0e1'
$PermaLink   = "https://github.com/$Owner/$RepoName/releases/latest/download/AppBlocker.apk"

function Step($n, $msg) { Write-Host ""; Write-Host "[$n] $msg" -ForegroundColor Cyan }
function Ok($msg)       { Write-Host "    OK  $msg" -ForegroundColor Green }
function Die($msg)      { Write-Host ""; Write-Host "STOPPED: $msg" -ForegroundColor Red; Write-Host "Nothing was published. Fix the issue above and run Publish again." -ForegroundColor Yellow; exit 1 }

try {
    Write-Host "=== Publish a new version of AppBlocker ===" -ForegroundColor White

    # --- 1. Ask what changed ------------------------------------------------------
    Step 1 "What changed in this version?"
    $note = $Note
    if ([string]::IsNullOrWhiteSpace($note)) {
        $note = Read-Host "    Type a short note (or just press Enter to skip)"
    }
    if ([string]::IsNullOrWhiteSpace($note)) { $note = "Bug fixes and improvements." }

    # --- 2. Bump the version automatically ----------------------------------------
    Step 2 "Bumping the version number"
    $text = [System.IO.File]::ReadAllText($GradleFile)
    if ($text -notmatch 'versionCode\s*=\s*(\d+)') { Die "Couldn't find versionCode in build.gradle.kts." }
    $oldCode = [int]$Matches[1]
    if ($text -notmatch 'versionName\s*=\s*"([^"]+)"') { Die "Couldn't find versionName in build.gradle.kts." }
    $oldName = $Matches[1]

    $newCode = $oldCode + 1
    $parts = $oldName.Split('.')
    $parts[-1] = ([int]$parts[-1] + 1).ToString()
    $newName = ($parts -join '.')
    $tag = "v$newName"

    $text = $text.Replace("versionCode = $oldCode", "versionCode = $newCode")
    $text = $text.Replace("versionName = `"$oldName`"", "versionName = `"$newName`"")
    [System.IO.File]::WriteAllText($GradleFile, $text)
    Ok "$oldName (code $oldCode)  ->  $newName (code $newCode)"

    # --- 3. Build the signed app --------------------------------------------------
    Step 3 "Building the app (this can take a minute or two)..."
    $env:JAVA_HOME = $Jbr
    & $GradleBat -p $Repo assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { Die "The build failed (see the messages above)." }
    if (-not (Test-Path $ApkBuilt)) { Die "The build finished but no APK was produced." }
    Ok "Built app-release.apk"

    # --- 4. Check it's signed with the right key (so updates install in place) -----
    # Modern APKs (minSdk 24) use APK Signature Scheme v2/v3, so we must use apksigner
    # (keytool -printcert only reads the old v1/JAR signature, which isn't present).
    Step 4 "Checking the app is signed correctly"
    $btDir = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $btDir) { Die "Couldn't find the Android build-tools (apksigner)." }
    $apksigner = Join-Path $btDir.FullName 'apksigner.bat'
    $certOut = & $apksigner verify --print-certs $ApkBuilt 2>&1 | Out-String
    if ($certOut -notmatch 'certificate SHA-256 digest:\s*([0-9A-Fa-f]+)') { Die "Couldn't read the app's signature." }
    $fp = $Matches[1].ToLower()
    if ($fp -ne $ReleaseCert) {
        Die "The app was signed with the WRONG key. Publishing it would break updates on your phone. (keystore.properties may be missing.)"
    }
    Ok "Signed with the AppBlocker release key"

    # --- 5. Copy to the permanent filename ----------------------------------------
    Step 5 "Preparing the download file"
    Copy-Item $ApkBuilt $ApkAsset -Force
    Ok "AppBlocker.apk ready"

    # --- 6. Record the change + save to git ---------------------------------------
    Step 6 "Saving the new version to GitHub (source)"
    $cl = [System.IO.File]::ReadAllText($Changelog)
    $entry = "## $tag`n- $note`n`n"
    $idx = $cl.IndexOf('## ')
    if ($idx -ge 0) { $cl = $cl.Substring(0, $idx) + $entry + $cl.Substring($idx) }
    else { $cl = $cl.TrimEnd() + "`n`n" + $entry }
    [System.IO.File]::WriteAllText($Changelog, $cl)

    git -C $Repo add -A;                      if ($LASTEXITCODE -ne 0) { Die "git add failed." }
    git -C $Repo commit -m "Release $tag";    if ($LASTEXITCODE -ne 0) { Die "git commit failed." }
    git -C $Repo tag $tag;                     if ($LASTEXITCODE -ne 0) { Die "git tag failed ($tag may already exist)." }
    git -C $Repo push;                         if ($LASTEXITCODE -ne 0) { Die "git push failed (check your internet)." }
    git -C $Repo push origin $tag;             if ($LASTEXITCODE -ne 0) { Die "pushing the tag failed." }
    Ok "Committed and tagged $tag"

    # --- 7. Publish the release on GitHub -----------------------------------------
    Step 7 "Publishing the release (this is what your phone downloads)"
    gh release create $tag $ApkAsset -t "AppBlocker $tag" -n $note
    if ($LASTEXITCODE -ne 0) { Die "Creating the GitHub release failed (is 'gh' signed in?)." }
    Ok "Release $tag is live"

    # --- 8. Confirm the download link works ---------------------------------------
    Step 8 "Double-checking the download link"
    Start-Sleep -Seconds 2
    $headers = curl.exe -sIL $PermaLink 2>&1 | Out-String
    if ($headers -match '\b200\b') { Ok "Download link is live" }
    else { Write-Host "    NOTE: the link may take a few seconds to go live - that's normal." -ForegroundColor Yellow }

    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host " DONE! AppBlocker $tag is published." -ForegroundColor Green
    Write-Host " Your phone will offer the update next time you open the app." -ForegroundColor Green
    Write-Host " Releases page: https://github.com/$Owner/$RepoName/releases/latest" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
}
catch {
    Die $_.Exception.Message
}
