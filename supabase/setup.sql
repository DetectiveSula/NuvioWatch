-- Nuvio Watch: shared family database.
-- Paste all of this into Supabase → SQL Editor → New query, then click Run.
-- Safe to run more than once.
--
-- Devices prove they belong to the family with an "x-family-key" header (derived from
-- the Nuvio account). Rows are only visible to, and can only be added by, requests whose
-- key hashes to the row's "family". Payloads are encrypted by the devices themselves.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.messages (
    id text primary key,
    family text not null,
    kind text not null,          -- playback, reaction, prediction, vote
    payload text not null,       -- encrypted on the device
    at bigint not null,          -- epoch milliseconds
    created_at timestamptz not null default now()
);
create index if not exists messages_family_at on public.messages (family, at);

create table if not exists public.dashboards (
    family text primary key,
    payload text not null,       -- encrypted summary for the web dashboard
    updated_at timestamptz not null default now()
);

-- The hash of the caller's x-family-key header.
create or replace function public.request_family() returns text
language sql stable
set search_path = ''
as $$
    select encode(
        extensions.digest(coalesce(current_setting('request.headers', true)::json ->> 'x-family-key', ''), 'sha256'),
        'hex'
    )
$$;

alter table public.messages enable row level security;
alter table public.dashboards enable row level security;

drop policy if exists "family reads messages" on public.messages;
drop policy if exists "family adds messages" on public.messages;
drop policy if exists "family reads dashboard" on public.dashboards;
drop policy if exists "family adds dashboard" on public.dashboards;
drop policy if exists "family updates dashboard" on public.dashboards;

create policy "family reads messages" on public.messages
    for select to anon using (family = public.request_family());
create policy "family adds messages" on public.messages
    for insert to anon with check (family = public.request_family());
create policy "family reads dashboard" on public.dashboards
    for select to anon using (family = public.request_family());
create policy "family adds dashboard" on public.dashboards
    for insert to anon with check (family = public.request_family());
create policy "family updates dashboard" on public.dashboards
    for update to anon using (family = public.request_family()) with check (family = public.request_family());

grant usage on schema public to anon;
grant select, insert on public.messages to anon;
grant select, insert, update on public.dashboards to anon;
grant execute on function public.request_family() to anon;

-- Keep the free tier small: drop live playback reports older than a year.
create or replace function public.prune_old_playback() returns void
language sql
set search_path = ''
as $$
    delete from public.messages where kind = 'playback' and at < (extract(epoch from now()) * 1000)::bigint - 31536000000;
$$;
