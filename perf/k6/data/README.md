# perf/k6/data — test data & token pools

This folder holds the **inputs** the authenticated journeys need. Nothing real is
committed — only `*.example.csv` templates (see `perf/.gitignore`).

## Files

| File | Purpose | Committed? |
|---|---|---|
| `citizen-tokens.example.csv` | Template for the citizen `>= T2` bearer pool | yes (template) |
| `citizen-tokens.csv` | Your real token pool (one bearer/line) | **no** (gitignored) |

## How tokens get here

The citizen and admin journeys cannot log in inside the load loop, because
`OtpService` delivers the OTP **out-of-band** and stores only a keyed **hash** —
the plaintext code is never in any API response (anti-enumeration, S-4). And
filing a report is **T2-gated**, so a fresh T1 signup token is not enough.

So tokens are minted **once**, before the run, by the seeding step. Two supported
ways (full detail in `perf/README.md` → *Seeding*):

1. **`auto-stub` verification + a perf OTP seam** (recommended for a throwaway
   perf env) — run the backend in a non-production perf profile that (a) uses a
   fixed/known OTP and (b) auto-approves verification so accounts reach T2/T3
   without NIDA. Then `seed-tokens.sh` mints N accounts and writes their tokens.
2. **DB-backed seed** — load a seed dataset of pre-verified `>= T2` accounts and
   issue tokens for them. Use this when you must test against `operator-assisted`
   verification and real seeded geography.

Whichever you use, the output is the same: a `citizen-tokens.csv` of `>= T2`
bearers, and a single `ADMIN_BEARER` for an ADMIN/ROOT account that has completed
staff TOTP.
