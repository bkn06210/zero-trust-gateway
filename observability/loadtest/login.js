// 로그인 CPU 고갈 실험.
// 매 요청 다른 이메일을 써서 요청 제한(이메일 기준)을 피하고, 요청마다 BCrypt를 돌게 한다.
// 초당 요청 수(arrival rate)를 5 → 60으로 천천히 올리며 응답 시간이 어디서 무너지는지 본다.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE; // 예: http://1.2.3.4:8080

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 5,            // 초당 5건에서 시작
      timeUnit: '1s',
      preAllocatedVUs: 300,    // 응답이 느려질 때 대기 요청을 받아낼 가상 사용자 풀
      maxVUs: 600,
      stages: [
        { target: 5,  duration: '20s' },
        { target: 10, duration: '20s' },
        { target: 20, duration: '20s' },
        { target: 30, duration: '20s' },
        { target: 45, duration: '20s' },
        { target: 60, duration: '20s' },
      ],
    },
  },
};

export default function () {
  const email = `atk-${__VU}-${__ITER}-${Date.now()}@example.com`;
  const res = http.post(
    `${BASE}/auth/login`,
    JSON.stringify({ email, password: 'wrong-password' }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '30s' },
  );
  // 없는 계정이므로 정상 동작이면 401. 서버가 밀리면 타임아웃이나 5xx가 늘어난다.
  check(res, { 'status is 401': (r) => r.status === 401 });
}
