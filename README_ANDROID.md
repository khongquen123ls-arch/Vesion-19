# Ku Sửu POS V18 – Android

Bản này giữ webapp Ku Sửu chạy trên Cloudflare + Supabase và thêm Android Native Printer Bridge trong APK.

## Cấu hình URL Cloudflare
Mở:
`app/src/main/java/com/kusuu/pos/MainActivity.kt`

Đổi:
`https://wispy-lake-0e02.tranbanguyen-ls2014.workers.dev/`

thành URL webapp Ku Sửu thật.

## In
- Điện thoại và máy in cùng Wi-Fi/LAN.
- Trong POS nhập IP máy in, port 9100.
- Nút kiểm tra dùng TCP trực tiếp từ Android.
- Nút in gửi ESC/POS raster trực tiếp tới IP:9100.
- Không cần PC, Node.js hay Print Bridge khi chạy APK.

## Build APK bằng Android Studio
1. Cài Android Studio.
2. Open thư mục `Ku_Suu_POS_V18_ANDROID`.
3. Chờ Gradle sync.
4. Nếu Android Studio hỏi SDK, cài Android SDK 35.
5. Build > Build APK(s).
6. APK debug nằm trong `app/build/outputs/apk/debug/app-debug.apk`.
7. Chép APK vào điện thoại và cài.

## Ghi chú
- V18 vẫn có thư mục print-bridge để tương thích bản web/PC cũ, nhưng APK không dùng nó.
- Máy in cần hỗ trợ ESC/POS raster qua TCP raw port 9100.

## BUILD APK ONLINE KHÔNG CẦN ANDROID STUDIO

Project đã có `.github/workflows/build-apk.yml`. Có thể upload project lên GitHub và dùng GitHub Actions để build APK trên máy chủ GitHub.

Xem `GITHUB_ONE_CLICK_BUILD.md` để làm theo từng bước.
