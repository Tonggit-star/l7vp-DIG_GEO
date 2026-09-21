# GeoJSON数据下载脚本
# 运行此脚本前请确保网络连接正常

$targetDir = "public\data"
if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir -Force
}

Write-Host "开始下载中国行政区域GeoJSON数据..." -ForegroundColor Green

# 数据源基础URL
$baseUrl = "https://npm.elemecdn.com/static-geo-atlas@0.1.0/geo-data/choropleth-data"

# 需要下载的文件列表
$files = @{
    "china_boundary.json" = "$baseUrl/country/100000_country_boundary.json"
    "china_province.json" = "$baseUrl/country/100000_country_province.json"
    "china_city.json" = "$baseUrl/country/100000_country_city.json"
    "china_district.json" = "$baseUrl/country/100000_country_district.json"
    "world_country.json" = "$baseUrl/world/all_world_country.json"
}

foreach ($file in $files.GetEnumerator()) {
    $filename = $file.Key
    $url = $file.Value
    Write-Host "下载 $filename ..." -ForegroundColor Yellow
    try {
        Invoke-WebRequest -Uri $url -OutFile "$targetDir\$filename" -UseBasicParsing
        Write-Host "  ✓ $filename 下载完成" -ForegroundColor Green
    } catch {
        Write-Host "  ✗ $filename 下载失败: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "数据下载完成！" -ForegroundColor Green
Write-Host "数据文件保存在: $targetDir" -ForegroundColor Cyan
