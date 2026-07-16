# Japan HTTPS edge（免备案）→ 反代国内 control-plane

## Connectivity (verified 2026-07-15)

| Item | Value |
|------|--------|
| SSH | `ssh -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes root@101.32.103.167` |
| Key | Same as mainland `ubuntu@119.45.36.208` (`~/.ssh/id_ed25519_witt`) |
| Hostname | `witt-surge` |
| Public IP | `101.32.103.167` (Japan) |
| Mainland CP | `119.45.36.208` → `/opt/loanagent` HTTP `:80` |

SSH BatchMode login succeeded (`echo OK`).

## What runs now (Option A live — 2026-07-15)

| Port | Process | Role |
|------|---------|------|
| **443** | `nginx` `stream` (`ssl_preread`) | SNI mux: `android.hashhub.com` → `:8443`; default → trojan `:4443` |
| **8443** | `nginx` HTTPS (`127.0.0.1`) | Let’s Encrypt + reverse proxy → `119.45.36.208:80` |
| **4443** | `trojan-go` (`127.0.0.1`) | Surge / Trojan (was public `:443`; password / Snell unchanged) |
| **80** | `nginx` | ACME + HTTP vhost / Trojan fallback |
| **22** | `sshd` | Admin |
| Snell | `snell.service` (`/root/snell-server.conf`) | Surge Snell |

Public hostname: **`android.hashhub.com`** (A → Japan `101.32.103.167`; avoid orange-cloud to wrong CF/DNSPod parking).

### ICP / Tencent Host trap (fixed 2026-07-16)

Mainland public `:80` returns **302 → DNSPod 备案页** when `Host` is an unfiled domain (e.g. `android.hashhub.com`). Japan edge itself was healthy; the break was **upstream Host forwarding**.

nginx must rewrite upstream Host to the mainland IP and keep the public name only in `X-Forwarded-Host`:

```nginx
proxy_set_header Host 119.45.36.208;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header X-Forwarded-Proto https;  # on :8443
```

Do **not** use `proxy_set_header Host $host` toward `119.45.36.208:80`. Signed media URLs use `PUBLIC_BASE_URL` env (not request Host), so this rewrite is safe.

`trojan-go` (`/etc/trojan-go/config.json`): listens **`127.0.0.1:4443`** only (backup taken at cutover). Surge clients still dial **`101.32.103.167:443`**; stream forwards non-loanagent SNI to trojan.

## Why this host

- Mainland Tencent CVM + **unfiled domain** → ICP / firewall blocks.
- Japan IP + domain A-record to `101.32.103.167` → **no mainland ICP**.
- Loanagent DPC requires `https://` on **port 443** and rejects IP literals (`InstallSecurity` / enrollment URL policy).

## Target architecture

```text
Phone / DPC
  --HTTPS:443-->  Japan 101.32.103.167
                    │
                    ├─ Trojan handshake (Surge) → existing trojan-go (unchanged passwords)
                    │
                    └─ Normal HTTPS (SNI = loanagent hostname)
                         → nginx reverse_proxy
                         → http://119.45.36.208:80  (mainland control-plane)
```

Mainland `.env` (cut over 2026-07-15):

```bash
PUBLIC_BASE_URL=https://android.hashhub.com
HTTPS_PUBLIC_BASE_URL=https://android.hashhub.com
```

QR / provisioning `trusted_control_plane_host` and `trusted_update_host` = **`android.hashhub.com`** (not an IP).

## Two safe ways to share :443 with Surge

### Option A — Prefer: nginx `stream` SNI multiplex (Surge client config unchanged)

1. Move `trojan-go` bind from `0.0.0.0:443` → `127.0.0.1:4443` only (systemd still manages it; **password / Snell untouched**).
2. nginx `stream { ssl_preread on; }` on public `443`:
   - SNI equals `<loanagent-hostname>` → `127.0.0.1:8443` (nginx `http` HTTPS vhost with Let’s Encrypt)
   - default → `127.0.0.1:4443` (trojan-go)
3. Loanagent vhost proxies to `http://119.45.36.208` with standard `Host` / `X-Forwarded-*` headers.

Surge clients keep connecting to `101.32.103.167:443` with their existing Trojan SNI; only the kernel listener moves behind stream.

### Option B — Use Trojan fallback only (simpler, but cert must satisfy browsers/DPC)

Keep trojan on public 443; non-Trojan traffic already hits nginx:80 after Trojan TLS terminate.

Then you **must** replace/extend `/etc/trojan-go/ssl/*` with a certificate whose SAN includes `<loanagent-hostname>`, and set `ssl.sni` accordingly.

- **Risk:** Surge clients that hard-code SNI=`101.32.103.167` may need a **one-time** client SNI update to the new hostname.
- Only choose B if you accept updating Surge SNI once.

**Recommendation for this project: Option A.**

## nginx reverse-proxy sketch (loanagent HTTPS vhost)

Upstream: mainland CP (stable private path preferred later; public HTTP ok for first cut).

```nginx
# Example only — install under /etc/nginx/sites-available/loanagent-cp
# TLS listen on 127.0.0.1:8443 when using Option A stream mux

upstream loanagent_cn {
    server 119.45.36.208:80;
    keepalive 16;
}

server {
    listen 127.0.0.1:8443 ssl;
    server_name <loanagent-hostname>;

    ssl_certificate     /etc/letsencrypt/live/<loanagent-hostname>/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/<loanagent-hostname>/privkey.pem;

    client_max_body_size 300m;  # APK / media uploads

    location / {
        proxy_http_version 1.1;
        # Tencent ICP webblock triggers when Host is an unfiled domain — use mainland IP.
        proxy_set_header Host 119.45.36.208;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_pass http://loanagent_cn;
        proxy_read_timeout 300s;
    }
}
```

HTTP-01 for Let’s Encrypt: temporarily serve `/.well-known/acme-challenge/` on nginx:80 (already public via Trojan fallback **or** open 80 directly — port 80 is already nginx). Prefer issuing certs with SNI/`certbot --nginx` carefully so Trojan stays up.

## Hard constraints (loanagent)

1. Public URL must be `https://<hostname>/...` **port 443** (or default HTTPS).
2. Hostname must resolve to **Japan** (or other non-ICP edge), not `119.45.36.208`, if the domain is unfiled.
3. Do **not** point an unfiled domain’s A record at the mainland Tencent IP.
4. Do **not** change Snell PSK / Trojan password unless rotating Surge clients on purpose.
5. After edge is live: set mainland `HTTPS_PUBLIC_BASE_URL`, redeploy/restart control-plane, regenerate DO QR with the new host.

## Cutover checklist

- [x] Domain A → `101.32.103.167` (`android.hashhub.com`; CF orange-cloud OK if SSL Full/strict)
- [x] Let’s Encrypt cert for hostname
- [x] Option A stream mux
- [x] nginx reverse proxy to `119.45.36.208:80`
- [x] From laptop: `curl -fsS https://android.hashhub.com/health` → `{"status":"ok",...}`
- [x] From phone network: same (operator verified 2026-07-15)
- [x] Mainland `.env` `PUBLIC_BASE_URL` + `HTTPS_PUBLIC_BASE_URL`
- [x] `sudo docker compose ... up -d --no-deps --force-recreate control-plane`
- [ ] New enrollment QR / trusted hosts
- [x] Confirm Surge still connects (Trojan + Snell) — operator verified 2026-07-15

## Ops commands (reference)

```bash
# Japan
ssh -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes root@101.32.103.167

# Mainland
ssh -i ~/.ssh/id_ed25519_witt -o IdentitiesOnly=yes ubuntu@119.45.36.208

# Health
curl -fsS https://android.hashhub.com/health
```

## Status

| Step | Status |
|------|--------|
| SSH to Japan with shared key | **PASS** (2026-07-15) |
| Inventory Surge / trojan / nginx | **PASS** |
| Option A: stream SNI + LE + reverse proxy | **PASS** (`android.hashhub.com`) |
| Public `https://android.hashhub.com/health` | **PASS** (200) |
| Mainland env cutover + CP recreate | **PASS** |
| Phone-network + Surge smoke | **PASS** (operator 2026-07-15) |
| DO QR / trusted hosts | **Operator** (next) |

Live edge: **`https://android.hashhub.com`** → Japan SNI mux → mainland CP.

## Related docs

- [`device-owner-operator-guide.md`](./device-owner-operator-guide.md) — DO / signing / push
- [`device-owner-pilot-runbook.md`](./device-owner-pilot-runbook.md) — day-of checklist
- Mainland compose: [`infra/compose.server.yaml`](../../infra/compose.server.yaml) (`HTTPS_PUBLIC_BASE_URL`)
