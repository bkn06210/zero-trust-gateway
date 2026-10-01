#!/usr/bin/env bash
# P1 시연: gateway(8080)와 auth-service(8081)가 떠 있는 상태에서 실행한다.
GW=http://localhost:8080
AUTH=http://localhost:8081
H="Content-Type: application/json"

show() { echo; echo "## $1"; }
req()  { curl -s -w "  [HTTP %{http_code}]\n" "$@"; }
field() { python -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

show "0. 회원 두 명 가입 (게이트웨이 경유, 이미 있으면 409)"
req -X POST $GW/auth/signup -H "$H" -d '{"email":"kim@example.com","password":"password123"}'
req -X POST $GW/auth/signup -H "$H" -d '{"email":"lee@example.com","password":"password123"}'

show "1. kim 로그인 → 토큰 발급"
TOKEN=$(curl -s -X POST $GW/auth/login -H "$H" -d '{"email":"kim@example.com","password":"password123"}' | field accessToken)
echo "  access token 길이: ${#TOKEN}"

show "2. 토큰 없이 내 정보 조회 → 401"
req $GW/users/me

show "3. 토큰으로 내 정보 조회 → 200"
req $GW/users/me -H "Authorization: Bearer $TOKEN"

show "4. 위조 토큰 (서명 마지막 글자를 바꿈) → 401"
LAST=${TOKEN: -1}; [ "$LAST" = "A" ] && NEW=B || NEW=A
req $GW/users/me -H "Authorization: Bearer ${TOKEN%?}$NEW"

show "5. kim 토큰 + 직접 쓴 X-User-Id: 2 헤더 → 여전히 kim (게이트웨이가 헤더를 버림)"
req $GW/users/me -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2"

show "6. 등록되지 않은 경로 → 404"
req $GW/nothing/here -H "Authorization: Bearer $TOKEN"

show "7. [취약점] 게이트웨이를 건너뛰고 8081에 직접, 토큰 없이 X-User-Id: 2 → lee 정보가 나옴"
req $AUTH/users/me -H "X-User-Id: 2"
