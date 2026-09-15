param(
    [Parameter(Mandatory = $true)]
    [string]$WorkerUrl,
    [string]$NodePath = '',
    [switch]$TokenFromClipboard,
    [switch]$DiagnoseOnly
)

$ErrorActionPreference = 'Stop'
$serviceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$configuration = Get-Content -LiteralPath (Join-Path $serviceRoot 'wrangler.toml') -Raw
$usernameMatch = [regex]::Match($configuration, '(?m)^BOT_USERNAME\s*=\s*"([A-Za-z][A-Za-z0-9_]{4,31})"\s*$')
if (-not $usernameMatch.Success -or -not $usernameMatch.Groups[1].Value.EndsWith('bot', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Сначала укажите настоящее BOT_USERNAME в wrangler.toml.'
}
$botUsername = $usernameMatch.Groups[1].Value
$workerAddress = [Uri]$WorkerUrl
if ($workerAddress.Scheme -ne 'https' -or $workerAddress.UserInfo -or $workerAddress.Query -or
    $workerAddress.Fragment -or $workerAddress.AbsolutePath -ne '/' -or -not $workerAddress.IsDefaultPort) {
    throw 'Нужен корневой HTTPS-адрес опубликованного Worker.'
}
if ($NodePath) {
    $nodeCommand = (Resolve-Path -LiteralPath $NodePath -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $nodeCommand -PathType Leaf)) { throw 'Не найден Node.js.' }
} else {
    $nodeCommand = (Get-Command node -ErrorAction Stop).Source
}
$wranglerScript = Join-Path $serviceRoot 'node_modules/wrangler/bin/wrangler.js'
if (-not (Test-Path -LiteralPath $wranglerScript -PathType Leaf)) {
    throw 'Сначала установите зависимости сервиса (pnpm install или npm install).'
}
$clipboardToken = $null
if ($TokenFromClipboard) {
    try {
        $clipboardToken = [string](Get-Clipboard -Raw -ErrorAction Stop)
        Set-Clipboard -Value '' -ErrorAction Stop
    } catch {
        $clipboardToken = $null
        throw 'Не удалось безопасно прочитать и очистить буфер обмена.'
    }
    $clipboardToken = $clipboardToken.Trim()
    if ($clipboardToken -notmatch '^\d{5,16}:[A-Za-z0-9_-]{20,}$') {
        $clipboardToken = $null
        throw 'В буфере нет корректного токена BotFather. Скопируйте только токен и повторите.'
    }
    $tokenSecure = ConvertTo-SecureString -String $clipboardToken -AsPlainText -Force
    $clipboardToken = $null
    Write-Host 'Токен прочитан, буфер обмена очищен.'
} else {
    $tokenSecure = Read-Host 'Вставьте токен BotFather (ввод скрыт, не отправляйте его в чат)' -AsSecureString
}
$webhookBytes = New-Object byte[] 32
$webhookRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $webhookRandom.GetBytes($webhookBytes) } finally { $webhookRandom.Dispose() }
$tokenPointer = [IntPtr]::Zero
$tokenText = $null
$secretText = $null
$stdinConfiguration = $null
$previousLogSanitize = [Environment]::GetEnvironmentVariable('WRANGLER_LOG_SANITIZE', 'Process')
Push-Location -LiteralPath $serviceRoot
try {
    # Never inherit a debug setting that writes secret HTTP bodies to disk.
    [Environment]::SetEnvironmentVariable('WRANGLER_LOG_SANITIZE', 'true', 'Process')
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($tokenSecure)
    $tokenText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
    $secretText = [Convert]::ToBase64String($webhookBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $stdinConfiguration = @{ token = $tokenText; secret = $secretText; botUsername = $botUsername } | ConvertTo-Json -Compress

    # Secrets travel only through redirected stdin; not argv, environment, files or history.
    if ($DiagnoseOnly) {
        $stdinConfiguration | & $nodeCommand 'scripts/register-webhook.mjs' $WorkerUrl '--diagnose'
        if ($LASTEXITCODE -ne 0) { throw 'Telegram не вернул безопасную диагностику webhook.' }
        return
    }
    $stdinConfiguration | & $nodeCommand 'scripts/register-webhook.mjs' $WorkerUrl '--check-only'
    if ($LASTEXITCODE -ne 0) { throw 'Проверка бота не пройдена. Секреты в Cloudflare не изменены.' }

    $tokenText | & $nodeCommand $wranglerScript 'secret' 'put' 'TELEGRAM_BOT_TOKEN'
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось сохранить токен бота в Cloudflare.' }
    $secretText | & $nodeCommand $wranglerScript 'secret' 'put' 'TELEGRAM_WEBHOOK_SECRET'
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось сохранить webhook-секрет в Cloudflare.' }

    $stdinConfiguration | & $nodeCommand 'scripts/register-webhook.mjs' $WorkerUrl
    if ($LASTEXITCODE -ne 0) { throw 'Webhook не подключён. Повторите настройку после исправления ошибки.' }
}
finally {
    [Environment]::SetEnvironmentVariable('WRANGLER_LOG_SANITIZE', $previousLogSanitize, 'Process')
    if ($tokenPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer) }
    $tokenText = $null
    $clipboardToken = $null
    $secretText = $null
    $stdinConfiguration = $null
    $tokenSecure.Dispose()
    [Array]::Clear($webhookBytes, 0, $webhookBytes.Length)
    Pop-Location
}
