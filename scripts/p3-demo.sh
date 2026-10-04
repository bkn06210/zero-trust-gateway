#!/usr/bin/env bash
# P3 시연: gateway(8080), auth-service(8081), redis가 떠 있는 상태에서 프로젝트 루트에서 실행한다.
GW=http://localhost:8080
H="Content-Type: application/json"

show() { echo; echo "## $1"; }
attempt() { curl -s -o /dev/null -w "%{http_code}" -X POST $GW/auth/login -H "$H" \
            -d "{\"email\":\"$1\",\"password\":\"$2\"}"; }

curl -s -o /dev/null -X POST $GW/auth/signup -H "$H" -d '{"email":"kim@example.com","password":"password123"}'

show "1. 무차별 대입: kim@example.com 에 틀린 비밀번호를 8번 연속 시도"
for i in $(seq 1 8); do
  printf "  %d번째: %s\n" "$i" "$(attempt kim@example.com wrong-$i)"
done

show "2. 차단된 상태에서 맞는 비밀번호를 넣어도 → 429"
echo "  $(attempt kim@example.com password123)"

show "3. Retry-After 헤더 (몇 초 뒤에 다시 시도 가능한지)"
curl -s -D - -o /dev/null -X POST $GW/auth/login -H "$H" \
  -d '{"email":"kim@example.com","password":"x"}' | grep -i "retry-after\|HTTP/"

show "4. 다른 계정은 영향 없음"
echo "  lee@example.com: $(attempt lee@example.com wrong)"

show "5. Redis에 실제로 들어 있는 카운터와 남은 시간(초)"
docker exec zero-trust-redis redis-cli --scan --pattern 'rl:login:*' | while read -r key; do
  printf "  %s = %s (TTL %ss)\n" "$key" "$(docker exec zero-trust-redis redis-cli GET "$key")" \
    "$(docker exec zero-trust-redis redis-cli TTL "$key")"
done
