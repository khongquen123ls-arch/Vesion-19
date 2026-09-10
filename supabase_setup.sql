-- KU SUU POS - SUPABASE SETUP
-- Chạy toàn bộ file này trong Supabase > SQL Editor.
-- App dùng publishable/anon key ở trình duyệt; KHÔNG đưa service_role key vào HTML.

create table if not exists public.pos_settings (
  id text primary key,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create table if not exists public.pos_tables (
  id text primary key,
  name text not null,
  sort_order integer not null default 0,
  updated_at timestamptz not null default now()
);

create table if not exists public.pos_dishes (
  id text primary key,
  name text not null,
  price numeric not null default 0,
  station text not null default 'kitchen' check (station in ('kitchen','bar')),
  updated_at timestamptz not null default now()
);

create table if not exists public.pos_ingredients (
  id text primary key,
  name text not null,
  unit text not null default '',
  qty numeric not null default 0,
  avg_cost numeric not null default 0,
  updated_at timestamptz not null default now()
);

create table if not exists public.pos_recipes (
  id text primary key,
  dish_id text not null,
  ingredient_id text not null,
  qty numeric not null default 0,
  updated_at timestamptz not null default now()
);

create table if not exists public.pos_orders (
  id text primary key,
  table_id text not null,
  table_name text not null default '',
  items jsonb not null default '[]'::jsonb,
  total numeric not null default 0,
  cost numeric not null default 0,
  created_at timestamptz not null default now(),
  paid boolean not null default false,
  kitchen_done boolean not null default false,
  kitchen_completed jsonb not null default '[]'::jsonb,
  bar_completed jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now()
);

create index if not exists pos_orders_created_at_idx on public.pos_orders(created_at desc);
create index if not exists pos_orders_table_id_idx on public.pos_orders(table_id);
create index if not exists pos_recipes_dish_id_idx on public.pos_recipes(dish_id);

-- Cập nhật updated_at tự động.
create or replace function public.pos_touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists pos_settings_touch on public.pos_settings;
create trigger pos_settings_touch before update on public.pos_settings for each row execute function public.pos_touch_updated_at();
drop trigger if exists pos_tables_touch on public.pos_tables;
create trigger pos_tables_touch before update on public.pos_tables for each row execute function public.pos_touch_updated_at();
drop trigger if exists pos_dishes_touch on public.pos_dishes;
create trigger pos_dishes_touch before update on public.pos_dishes for each row execute function public.pos_touch_updated_at();
drop trigger if exists pos_ingredients_touch on public.pos_ingredients;
create trigger pos_ingredients_touch before update on public.pos_ingredients for each row execute function public.pos_touch_updated_at();
drop trigger if exists pos_recipes_touch on public.pos_recipes;
create trigger pos_recipes_touch before update on public.pos_recipes for each row execute function public.pos_touch_updated_at();
drop trigger if exists pos_orders_touch on public.pos_orders;
create trigger pos_orders_touch before update on public.pos_orders for each row execute function public.pos_touch_updated_at();

-- Bật RLS. Đây là app nội bộ dùng publishable/anon key trực tiếp từ browser.
-- Chính sách dưới đây cho phép client POS đọc/ghi các bảng của quán.
-- Nếu sau này cần nhiều nhân viên/tài khoản và phân quyền, ta sẽ nâng cấp sang Supabase Auth + RLS theo user.
alter table public.pos_settings enable row level security;
alter table public.pos_tables enable row level security;
alter table public.pos_dishes enable row level security;
alter table public.pos_ingredients enable row level security;
alter table public.pos_recipes enable row level security;
alter table public.pos_orders enable row level security;

drop policy if exists pos_settings_all on public.pos_settings;
create policy pos_settings_all on public.pos_settings for all to anon, authenticated using (true) with check (true);
drop policy if exists pos_tables_all on public.pos_tables;
create policy pos_tables_all on public.pos_tables for all to anon, authenticated using (true) with check (true);
drop policy if exists pos_dishes_all on public.pos_dishes;
create policy pos_dishes_all on public.pos_dishes for all to anon, authenticated using (true) with check (true);
drop policy if exists pos_ingredients_all on public.pos_ingredients;
create policy pos_ingredients_all on public.pos_ingredients for all to anon, authenticated using (true) with check (true);
drop policy if exists pos_recipes_all on public.pos_recipes;
create policy pos_recipes_all on public.pos_recipes for all to anon, authenticated using (true) with check (true);
drop policy if exists pos_orders_all on public.pos_orders;
create policy pos_orders_all on public.pos_orders for all to anon, authenticated using (true) with check (true);

-- Cho phép Data API roles sử dụng bảng.
grant select, insert, update, delete on public.pos_settings to anon, authenticated;
grant select, insert, update, delete on public.pos_tables to anon, authenticated;
grant select, insert, update, delete on public.pos_dishes to anon, authenticated;
grant select, insert, update, delete on public.pos_ingredients to anon, authenticated;
grant select, insert, update, delete on public.pos_recipes to anon, authenticated;
grant select, insert, update, delete on public.pos_orders to anon, authenticated;

-- Realtime cho đồng bộ nhiều máy/điện thoại.
alter table public.pos_settings replica identity full;
alter table public.pos_tables replica identity full;
alter table public.pos_dishes replica identity full;
alter table public.pos_ingredients replica identity full;
alter table public.pos_recipes replica identity full;
alter table public.pos_orders replica identity full;

do $$
begin
  begin alter publication supabase_realtime add table public.pos_settings; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.pos_tables; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.pos_dishes; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.pos_ingredients; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.pos_recipes; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.pos_orders; exception when duplicate_object then null; end;
end $$;

-- Sau khi chạy SQL, bảng sẽ trống. Lần đầu kết nối app sẽ hỏi anh có muốn
-- đẩy dữ liệu đang có trên máy hiện tại lên Supabase hay không.
-- Ku Sửu POS: hóa đơn chuyên nghiệp / thu ngân / số hóa đơn
-- Chạy 1 lần trên Supabase SQL Editor nếu database hiện tại chưa có các cột này.
alter table public.pos_orders add column if not exists payment_group_id text;
alter table public.pos_orders add column if not exists paid_at timestamptz;
alter table public.pos_orders add column if not exists invoice_no text;
alter table public.pos_orders add column if not exists cashier text;
alter table public.pos_orders add column if not exists payment_method text;
create index if not exists pos_orders_invoice_no_idx on public.pos_orders(invoice_no);
create index if not exists pos_orders_payment_group_idx on public.pos_orders(payment_group_id);
