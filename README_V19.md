# Ku Sửu POS V19

V19 giữ nguyên Cloudflare + Supabase + toàn bộ logic POS V18, đồng thời nâng 3 phần:

1. Android launcher icon dùng logo Ku Sửu.
2. Hóa đơn 80mm mới: số hóa đơn, ngày giờ, bàn, món, số lượng, tổng tiền, phương thức thanh toán, lời cảm ơn.
3. In nhanh native ESC/POS: Android nhận JSON hóa đơn nhỏ và tự dựng lệnh ESC/POS, không còn chuyển toàn bộ hóa đơn thành bitmap/Base64 ở đường in chính. `printRaster` vẫn giữ để tương thích/fallback.

## Build GitHub
- Giữ `.github/workflows/build-apk.yml` bản đã build thành công với Java 17 + Android SDK 35 + Gradle 8.9.
- Đổi `versionCode`/`versionName` thành 19/19.0 đã có trong `app/build.gradle.kts`.

## Máy in
- IP máy in: ví dụ `192.168.1.120`
- Port: `9100`
- Điện thoại và máy in cùng Wi-Fi/LAN.
- APK V19 in trực tiếp, không cần PC.

## Lưu ý font tiếng Việt
V19 chọn ESC/POS code page 19 + Windows-1258 cho đường in text. Một số máy in giá rẻ có mapping code page khác; nếu chữ tiếng Việt bị sai, có thể đổi code page trong native bridge hoặc dùng raster fallback.

## iPhone
iOS không thể build từ APK Android. V19 giữ PWA manifest/icon cho iPhone Add to Home Screen; bản native iOS cần project Xcode/Swift riêng và module TCP printer riêng.
