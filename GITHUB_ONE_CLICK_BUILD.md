# Ku Sửu POS V18 — One-Click Build Online

Project này có sẵn GitHub Actions để build APK trên máy chủ GitHub. Không cần cài Android Studio trên máy tính.

## Cách dùng

1. Tạo một repository GitHub mới, ví dụ `ku-suu-pos-v18`.
2. Upload toàn bộ nội dung của project này vào repository.
3. Vào tab **Actions**.
4. Chọn workflow **Build Ku Suu POS APK**.
5. Bấm **Run workflow**.
6. Chờ job màu xanh hoàn tất.
7. Mở phần **Artifacts** của run đó.
8. Tải `Ku-Suu-POS-V18-APK`.
9. Giải nén file ZIP artifact để lấy `app-debug.apk`.
10. Chép APK vào điện thoại Android và cài.

## Build tự động

Workflow cũng tự chạy khi push vào nhánh `main` hoặc `master`.

## Thành phần build

- JDK 17
- Android SDK 35
- Build Tools 35.0.0
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21

APK debug được tạo tại:

`app/build/outputs/apk/debug/app-debug.apk`

## Lưu ý

APK này đã được cấu hình để tải Ku Sửu POS từ Cloudflare:

`https://wispy-lake-0e02.tranbanguyen-ls2014.workers.dev/`

Phần native Android `KuSuuPrinter` giữ chức năng kiểm tra IP máy in và gửi dữ liệu ESC/POS trực tiếp tới máy in TCP/IP port 9100.
