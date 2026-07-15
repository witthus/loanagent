# Japan HTTPS edge (loanagent)

When configuring Device Owner enrollment or remote Agent upgrades without a mainland ICP-filed domain, use the Japan Surge host as the public HTTPS edge and reverse-proxy to the mainland control-plane.

**Live (2026-07-15, Option A):** `https://android.hashhub.com` → `101.32.103.167` (SNI mux) → `119.45.36.208:80`.

- Inventory, SSH, ports, Surge coexistence (trojan-go / nginx / Snell): [`ops/m0/japan-https-edge.md`](../ops/m0/japan-https-edge.md)
- Operator DO tutorial (points at Japan edge as preferred HTTPS): [`ops/m0/device-owner-operator-guide.md`](../ops/m0/device-owner-operator-guide.md)

Do not point an unfiled domain at `119.45.36.208`. Do not take over Japan `:443` in a way that breaks `trojan-go` / Snell; use SNI multiplex (Option A in the ops doc).
