param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot

try {
    if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
        $GradleArgs = @("assembleDebug")
    } elseif ($GradleArgs.Count -eq 1 -and $GradleArgs[0] -match "\s") {
        $GradleArgs = $GradleArgs[0] -split "\s+"
    }

    if (-not $env:JAVA_HOME) {
        $bundledJdk = Join-Path (Split-Path -Parent $projectRoot) "tools\jdk21\jdk-21.0.13+9"
        if (Test-Path $bundledJdk) {
            $env:JAVA_HOME = $bundledJdk
        }
    }

    if (-not $env:ANDROID_HOME -and -not (Test-Path (Join-Path $projectRoot "local.properties"))) {
        $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path $defaultSdk) {
            $env:ANDROID_HOME = $defaultSdk
        } else {
            Write-Warning "No ANDROID_HOME or local.properties detected. Gradle tasks requiring Android SDK Platform 35 will fail until configured."
        }
    }

    & (Join-Path $projectRoot "gradlew.bat") @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
