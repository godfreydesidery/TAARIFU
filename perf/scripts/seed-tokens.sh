#!/usr/bin/env bash
# =============================================================================
# Taarifu perf — citizen token seeding helper.
#
# Mints N citizen access tokens into perf/k6/data/citizen-tokens.csv for the
# load harness, using the backend's PERF OTP seam.
#
# WHY a seam is required: OtpService delivers the OTP out-of-band and stores only
# a keyed HASH (S-4) — a script cannot read the code from the API. And filing a
# report is T2-gated. So this helper assumes the backend is running in a
# NON-PRODUCTION perf configuration that:
#   * accepts a FIXED OTP (PERF_OTP) for signup verification, AND
#   * reaches T2 (e.g. TAARIFU_VERIFICATION_PROVIDER=auto-stub + a perf profile
#     that auto-approves the T2 contact-verify / verification step).
# This is a BACKEND change the team opts into for a throwaway perf env; this
# repo's harness never modifies app source. See perf/README.md "Seeding".
#
# If your perf env does NOT have such a seam, mint tokens via your DB seed
# instead and just write them (one per line) into perf/k6/data/citizen-tokens.csv.
#
# Usage:
#   BASE_URL=http://localhost:8081 PERF_OTP=000000 COUNT=20 ./perf/scripts/seed-tokens.sh
#
# Requires: bash, curl, jq.
# =============================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
API_BASE="${BASE_URL}/api/v1"
PERF_OTP="${PERF_OTP:-}"
COUNT="${COUNT:-10}"
OUT="${OUT:-$(dirname "$0")/../k6/data/citizen-tokens.csv}"

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required (brew/apt install jq)." >&2
  exit 1
fi
if [ -z "${PERF_OTP}" ]; then
  echo "ERROR: PERF_OTP is required (the fixed code your perf OTP seam accepts)." >&2
  echo "       This script needs a backend perf seam — see the header comment." >&2
  exit 1
fi

echo "# Taarifu perf citizen tokens — generated $(date -u +%FT%TZ)" > "${OUT}"
echo "# DO NOT COMMIT. ${COUNT} accounts against ${API_BASE}" >> "${OUT}"

minted=0
for i in $(seq 1 "${COUNT}"); do
  # 9-digit subscriber number -> valid E.164 TZ phone.
  phone="+255$(( (RANDOM % 900000000) + 100000000 ))"

  challenge=$(curl -fsS -X POST "${API_BASE}/auth/otp/request" \
    -H 'Content-Type: application/json' \
    -d "{\"phone\":\"${phone}\"}" | jq -r '.data.challengeId')
  if [ -z "${challenge}" ] || [ "${challenge}" = "null" ]; then
    echo "WARN: no challengeId for ${phone}, skipping" >&2
    continue
  fi

  token=$(curl -fsS -X POST "${API_BASE}/auth/signup" \
    -H 'Content-Type: application/json' \
    -d "{\"challengeId\":\"${challenge}\",\"code\":\"${PERF_OTP}\"}" \
    | jq -r '.data.tokens.accessToken')
  if [ -z "${token}" ] || [ "${token}" = "null" ]; then
    echo "WARN: signup did not return a token for ${phone}, skipping" >&2
    continue
  fi

  # NOTE: this yields a T1 token. To file reports the account must be T2.
  # Raising to T2 (email/profile verify) is env-specific; in an auto-stub perf
  # profile it may be automatic. Verify with the perf check below before relying
  # on these for the citizen WRITE journey.
  echo "${token}" >> "${OUT}"
  minted=$((minted + 1))
done

echo "Wrote ${minted} token(s) to ${OUT}" >&2
echo "Validate tier with: curl -s ${API_BASE}/profiles/me/tier -H \"Authorization: Bearer <token>\" | jq" >&2
