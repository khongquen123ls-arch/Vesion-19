# Ku Sửu Print Bridge – in máy in LAN/Wi‑Fi 80mm

V17 được thiết kế để dùng giống cách khai báo máy in LAN của CUKCUK: trong POS anh chỉ cần nhập **IP máy in** (mặc định port 9100). Print Bridge là thành phần kỹ thuật chạy nền trên máy tính để trình duyệt có thể mở TCP tới máy in.

## Cài một lần
1. Cài Node.js LTS trên Windows.
2. Mở `start-windows.bat`.
3. Giữ cửa sổ Print Bridge chạy trong khi sử dụng POS.

Kiểm tra: mở `http://127.0.0.1:3188/health` và thấy `ok:true`.

## Trong Ku Sửu
Vào **Sao lưu & Khôi phục dữ liệu → THIẾT LẬP MÁY IN LAN · 80mm**.
- Nhập IP máy in, ví dụ `192.168.1.120`.
- Port: `9100`.
- Bấm **LƯU MÁY IN**.
- Bấm **KIỂM TRA KẾT NỐI**. Nút này kiểm tra TCP thật tới máy in.
- Bấm **IN HÓA ĐƠN TEST**.

Máy tính chạy POS và máy in phải cùng mạng Wi‑Fi/LAN. Nếu POS chạy trên máy khác với Print Bridge, cần đổi URL Bridge trong mã cấu hình hoặc triển khai bridge trên máy đang chạy POS; V17 mặc định tối ưu cho POS + Bridge trên cùng máy Windows.
