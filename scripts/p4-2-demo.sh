#!/usr/bin/env bash
# P4-2 시연: 일부러 비밀값과 취약한 코드를 넣은 커밋을 만들고, CI와 같은 도구(gitleaks, semgrep)가 잡는지 본다.
# 공개 저장소 이력을 더럽히지 않도록 임시 브랜치에서만 하고 끝나면 지운다. 프로젝트 루트에서 실행.
set -u
# Windows Git Bash가 /repo 같은 경로를 C:/Program Files/Git/repo 로 바꾸는 것을 막는다.
export MSYS_NO_PATHCONV=1

ORIG=$(git rev-parse --abbrev-ref HEAD)
DEMO_DIR=auth-service/src/main/java/com/zerotrust/auth/demo
git checkout -q -b demo/insecure

echo "## 1. 일부러 넣는 것"
mkdir -p "$DEMO_DIR"
cat > "$DEMO_DIR/InsecureDemo.java" <<'EOF'
package com.zerotrust.auth.demo;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class InsecureDemo {
    // (1) 코드에 박힌 AWS 키 — 형식만 맞는 무작위 값. AWS 문서의 예시 키(AKIAIOSFODNN7EXAMPLE)는 gitleaks가 알고 무시한다.
    static final String AWS_SECRET = "AKIAZ7Q3XK9PLM2NB4RT";

    // (2) 사용자 입력을 그대로 이어붙인 SQL — SQL 인젝션
    public void find(Connection conn, String email) throws SQLException {
        Statement st = conn.createStatement();
        st.executeQuery("SELECT * FROM users WHERE email = '" + email + "'");
    }
}
EOF
git add "$DEMO_DIR" && git commit -q -m "demo: 일부러 넣은 비밀값과 SQL 인젝션 (시연용, 머지하지 않음)"
echo "  커밋 완료: $(git log --oneline -1)"

echo; echo "## 2. gitleaks — 커밋 이력에서 비밀값 탐지"
docker run --rm -v "$(pwd):/repo" zricethezav/gitleaks:latest git /repo --no-banner --redact 2>&1 \
  | grep -E "Finding|Secret|RuleID|File|leaks found|no leaks" | head -8

echo; echo "## 3. semgrep — 코드 정적 분석"
docker run --rm -v "$(pwd):/src" -w /src semgrep/semgrep semgrep scan --config p/java --config p/owasp-top-ten --error "$DEMO_DIR" 2>&1 \
  | grep -E "❯❱|┆|Findings:" | head -12

echo; echo "## 4. 정리 — 임시 브랜치 삭제"
git checkout -q "$ORIG" && git branch -q -D demo/insecure && rm -rf "$DEMO_DIR"
echo "  현재 브랜치: $(git rev-parse --abbrev-ref HEAD), 시연 파일 남아 있나: $([ -d "$DEMO_DIR" ] && echo 예 || echo 아니오)"
