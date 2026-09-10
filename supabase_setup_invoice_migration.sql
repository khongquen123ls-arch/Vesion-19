-- Ku Sửu POS: hóa đơn chuyên nghiệp / thu ngân / số hóa đơn
-- Chạy 1 lần trên Supabase SQL Editor nếu database hiện tại chưa có các cột này.
alter table public.pos_orders add column if not exists payment_group_id text;
alter table public.pos_orders add column if not exists paid_at timestamptz;
alter table public.pos_orders add column if not exists invoice_no text;
alter table public.pos_orders add column if not exists cashier text;
alter table public.pos_orders add column if not exists payment_method text;
create index if not exists pos_orders_invoice_no_idx on public.pos_orders(invoice_no);
create index if not exists pos_orders_payment_group_idx on public.pos_orders(payment_group_id);
