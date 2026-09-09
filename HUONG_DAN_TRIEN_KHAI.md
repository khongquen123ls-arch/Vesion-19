# KU SỬU POS V17 – MÁY IN LAN/WI‑FI 80mm

V17 giữ nguyên toàn bộ logic nghiệp vụ V15/V16 và đơn giản hóa cách cấu hình máy in theo kiểu CUKCUK: anh chỉ cần nhập IP máy in LAN/Wi‑Fi và port (mặc định 9100).

## 1. Cài Print Bridge một lần trên Windows
- Cài Node.js LTS.
- Mở thư mục `print-bridge`.
- Chạy `start-windows.bat`.
- Mở `http://127.0.0.1:3188/health`; nếu thấy `ok:true` là Bridge đang chạy.

## 2. Cấu hình máy in
Vào **Sao lưu & Khôi phục dữ liệu → THIẾT LẬP MÁY IN LAN · 80mm**.
- Địa chỉ IP máy in: ví dụ `192.168.1.120`.
- Cổng: `9100` (hoặc port TCP mà model máy in sử dụng).
- Bấm **LƯU MÁY IN**.
- Bấm **KIỂM TRA KẾT NỐI**.
- Khi báo xanh, bấm **IN HÓA ĐƠN TEST**.

## 3. Mô hình mạng
`Ku Sửu POS → Print Bridge trên máy Windows → Wi‑Fi/LAN → Máy in 80mm`

Máy in không cần cắm USB vào máy tính; chỉ cần máy in và máy tính cùng mạng và máy in nhận raw TCP/IP (thường 9100).

## 4. Nếu không in
- Kiểm tra IP máy in.
- Kiểm tra máy in đang kết nối Wi‑Fi/LAN.
- Kiểm tra port 9100.
- Đảm bảo Windows Firewall không chặn Node.js/port 3188 nếu cần truy cập Bridge từ thiết bị khác.
