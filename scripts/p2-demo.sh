#!/usr/bin/env bash
# P2 시연: gateway(8080)와 auth-service(8081)가 떠 있는 상태에서 프로젝트 루트에서 실행한다.
GW=http://localhost:8080
AUTH=http://localhost:8081
H="Content-Type: application/json"

# 초기 관리자 계정은 .env에서 읽는다 (화면에 출력하지 않는다).
ADMIN_EMAIL=$(grep '^ADMIN_EMAIL=' .env | cut -d= -f2-)
ADMIN_PASSWORD=$(grep '^ADMIN_PASSWORD=' .env | cut -d= -f2-)

show() { echo; echo "## $1"; }
req()  { curl -s -w "  [HTTP %{http_code}]\n" "$@"; }
token() { curl -s -X POST $GW/auth/login -H "$H" -d "{\"email\":\"$1\",\"password\":\"$2\"}" \
          | python -c "import sys,json; print(json.load(sys.stdin)['accessToken'])"; }

curl -s -o /dev/null -X POST $GW/auth/signup -H "$H" -d '{"email":"kim@example.com","password":"password123"}'
USER_TOKEN=$(token kim@example.com password123)
ADMIN_TOKEN=$(token "$ADMIN_EMAIL" "$ADMIN_PASSWORD")

show "1. 토큰 없이 관리자 API → 401 (누구인지 모름)"
req $GW/admin/users

show "2. USER 토큰으로 관리자 API → 403 (누구인지는 알지만 권한 없음)"
req $GW/admin/users -H "Authorization: Bearer $USER_TOKEN"

show "3. USER 토큰 + 직접 쓴 X-User-Role: ADMIN → 403 (헤더로는 권한을 올릴 수 없음)"
req $GW/admin/users -H "Authorization: Bearer $USER_TOKEN" -H "X-User-Role: ADMIN"

show "4. ADMIN 토큰으로 관리자 API → 200"
req $GW/admin/users -H "Authorization: Bearer $ADMIN_TOKEN"

show "5. USER 토큰으로 일반 API → 200 (권한 검사가 일반 경로를 막지 않음)"
req $GW/users/me -H "Authorization: Bearer $USER_TOKEN"

show "6. [취약점] 게이트웨이를 건너뛰고 8081에 직접 X-User-Role: ADMIN → 200"
req $AUTH/admin/users -H "X-User-Role: ADMIN"
