# Removes EvrimaServerBot slash roots from THIS Discord application (global + every guild the bot is in).
#
# Discord often returns 40333 "internal network error" for Invoke-RestMethod because PowerShell sends a
# browser-like User-Agent and Cloudflare blocks it. This script uses curl.exe first (Windows 10+).
#
# Usage:
#   .\remove-evrima.ps1 -BotToken "YOUR_BOT_TOKEN"

param(
    [string] $ApplicationId = "1486185207509029036",
    [Parameter(Mandatory = $true)]
    [string] $BotToken
)

$ErrorActionPreference = "Stop"

# Same format many libraries use; must not look like a full browser UA.
$UA = "DiscordBot (https://github.com/discord/discord-api-docs, 10)"
$evrima = @("evrima", "evrima-mod", "evrima-admin", "evrima-head")
$app = "https://discord.com/api/v10/applications/$ApplicationId"

function Test-CurlAvailable {
    return [bool](Get-Command curl.exe -ErrorAction SilentlyContinue)
}

function Invoke-DiscordGet {
    param([string] $Uri)
    if (Test-CurlAvailable) {
        $out = & curl.exe -sS -H "Authorization: Bot $BotToken" -H "User-Agent: $UA" $Uri
        if ($LASTEXITCODE -ne 0) { throw "GET failed (curl exit $LASTEXITCODE): $Uri" }
        if ([string]::IsNullOrWhiteSpace($out)) { return @() }
        return $out | ConvertFrom-Json
    }
    $headers = @{
        Authorization = "Bot $BotToken"
        "User-Agent"  = $UA
    }
    return Invoke-RestMethod -Uri $Uri -Headers $headers -Method Get
}

function Invoke-DiscordDelete {
    param([string] $Uri)
    if (Test-CurlAvailable) {
        & curl.exe -sS -f -X DELETE -H "Authorization: Bot $BotToken" -H "User-Agent: $UA" $Uri | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "DELETE failed (curl exit $LASTEXITCODE): $Uri" }
        return
    }
    $headers = @{
        Authorization = "Bot $BotToken"
        "User-Agent"  = $UA
    }
    Invoke-RestMethod -Uri $Uri -Headers $headers -Method Delete | Out-Null
}

function Remove-Listed {
    param([string] $ListUrl, [string] $DeletePrefix)
    $cmds = Invoke-DiscordGet -Uri $ListUrl
    if ($null -eq $cmds) { return }
    if ($cmds -isnot [System.Array]) { $cmds = @($cmds) }
    foreach ($c in $cmds) {
        if ($evrima -contains $c.name) {
            Invoke-DiscordDelete -Uri "$DeletePrefix/$($c.id)"
            Write-Host "Removed: $($c.name)"
        }
    }
}

if (Test-CurlAvailable) {
    Write-Host "Using curl.exe for Discord API (avoids PowerShell 40333)."
} else {
    Write-Host "curl.exe not found; using Invoke-RestMethod with bot User-Agent."
}

Write-Host "=== GLOBAL ==="
Remove-Listed "$app/commands" "$app/commands"

Write-Host "=== GUILDS ==="
$guilds = Invoke-DiscordGet -Uri "https://discord.com/api/v10/users/@me/guilds"
if ($null -eq $guilds) { $guilds = @() }
if ($guilds -isnot [System.Array]) { $guilds = @($guilds) }
foreach ($g in $guilds) {
    try {
        Remove-Listed "$app/guilds/$($g.id)/commands" "$app/guilds/$($g.id)/commands"
    } catch {
        Write-Host "Guild $($g.id): $($_.Exception.Message)"
    }
}
Write-Host "Done."
