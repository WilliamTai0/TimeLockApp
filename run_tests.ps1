# TimeLock 一鍵自動化測試腳本
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "🚀 正在執行 TimeLock 新功能自動化單元測試..." -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest --info

if ($LASTEXITCODE -eq 0) {
    Write-Host "
✅ 所有測試通過！(BUILD SUCCESSFUL)" -ForegroundColor Green
    Write-Host "測試項目涵蓋：" -ForegroundColor Yellow
    Write-Host " 1. 每日加鐘券限量 1 次防刷機制 (Overtime Pass Token)" -ForegroundColor White
    Write-Host " 2. 加鐘券延長 30 分鐘會話累計 (Duration Extension)" -ForegroundColor White
    Write-Host " 3. 10 分鐘冷靜延遲解除有效性 (10-Minute Anti-Bypass)" -ForegroundColor White
    Write-Host " 4. 10 分鐘冷靜期內點擊撤銷恢復 (Cancellation & Rollback)" -ForegroundColor White
} else {
    Write-Host "
❌ 測試未通過，請檢查上方錯誤日誌。" -ForegroundColor Red
}
