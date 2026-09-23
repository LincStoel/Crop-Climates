# Builds the mod and launches the NeoForge dev client for manual testing.
# Usage: right-click > Run with PowerShell, or from a terminal: .\run-client.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

.\gradlew.bat runClient
