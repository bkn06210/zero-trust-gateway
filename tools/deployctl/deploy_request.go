package main

import (
	"context"
	"flag"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path"
	"strings"

	"github.com/anthropics/anthropic-sdk-go/v2"
	"gopkg.in/yaml.v3"
)

// 배포 요청서는 두 부분으로 나뉜다.
//   사실: 기록에서 그대로 가져온다. 어느 커밋에서 어느 커밋으로, 그 사이의 커밋 목록, 바뀐 파일.
//   해석: 바뀐 코드를 모델이 읽고 풀어 쓴다. 틀릴 수 있으므로 사실과 섞지 않고 따로 표시한다.

type compareResult struct {
	Commits []struct {
		ShortID    string `json:"short_id"`
		Title      string `json:"title"`
		AuthorName string `json:"author_name"`
	} `json:"commits"`
	Diffs []struct {
		OldPath string `json:"old_path"`
		NewPath string `json:"new_path"`
		Diff    string `json:"diff"`
	} `json:"diffs"`
	CompareTimeout bool `json:"compare_timeout"`
}

// imageRefAt은 git에 기록된 특정 시점의 값 파일에서 이미지 정보를 읽는다.
func imageRefAt(ref, valuesPath string) (imageRef, error) {
	out, err := exec.Command("git", "show", ref+":"+valuesPath).Output()
	if err != nil {
		return imageRef{}, fmt.Errorf("git show %s:%s: %w", ref, valuesPath, err)
	}
	var v struct {
		Image imageRef `yaml:"image"`
	}
	if err := yaml.Unmarshal(out, &v); err != nil {
		return imageRef{}, err
	}
	return v.Image, nil
}

// buildDeployRequest는 값 파일 하나에 대한 요청서를 만든다.
func buildDeployRequest(ctx context.Context, c *client, ai anthropic.Client, model, registry, baseRef, valuesPath string) (string, error) {
	service := strings.TrimSuffix(path.Base(valuesPath), ".yaml")
	var b strings.Builder
	fmt.Fprintf(&b, "# 배포 요청서: %s\n\n", service)

	newImg, err := readImageRef(valuesPath)
	if err != nil {
		return "", err
	}
	oldImg, err := imageRefAt(baseRef, valuesPath)
	if err != nil {
		return "", err
	}
	if oldImg.Commit == newImg.Commit && oldImg.Digest == newImg.Digest {
		b.WriteString("이 변경 요청은 이 서비스의 이미지를 바꾸지 않습니다.\n")
		return b.String(), nil
	}
	if oldImg.Commit == "" {
		return "", fmt.Errorf("%s의 %s에 image.commit이 없어 비교할 수 없습니다", baseRef, valuesPath)
	}

	rest, ok := strings.CutPrefix(newImg.Repository, registry+"/")
	i := strings.LastIndex(rest, "/")
	if !ok || i <= 0 {
		return "", fmt.Errorf("image.repository 모양이 맞지 않습니다: %q", newImg.Repository)
	}
	project := rest[:i]

	// 사실 부분: 코드 저장소에 두 커밋의 차이를 묻는다.
	var cmp compareResult
	q := url.Values{"from": {oldImg.Commit}, "to": {newImg.Commit}}
	if err := c.getJSON(projectPath(project)+"/repository/compare", q, &cmp); err != nil {
		return "", err
	}
	if cmp.CompareTimeout {
		return "", fmt.Errorf("GitLab이 차이 계산을 끝내지 못했습니다. 일부만 가지고 요약하지 않습니다")
	}

	// 이 이미지에 들어가는 것은 서비스 폴더의 코드이고, 이미지를 만들고 검사하는 방법은 CI 정의에 있다.
	// 문서나 다른 서비스의 변경은 이 이미지와 무관하므로 요약 대상에서 뺀다. 뺀 개수는 요청서에 적는다.
	var diff strings.Builder
	var files []string
	skipped := 0
	for _, d := range cmp.Diffs {
		if !strings.HasPrefix(d.NewPath, service+"/") && d.NewPath != ".gitlab-ci.yml" {
			skipped++
			continue
		}
		if d.Diff == "" {
			return "", fmt.Errorf("%s의 차이가 비어 있습니다. 너무 크거나 이진 파일입니다. 일부만 가지고 요약하지 않습니다", d.NewPath)
		}
		files = append(files, d.NewPath)
		fmt.Fprintf(&diff, "diff --git a/%s b/%s\n--- a/%s\n+++ b/%s\n%s", d.OldPath, d.NewPath, d.OldPath, d.NewPath, d.Diff)
	}

	b.WriteString("## 사실\n\n기록에서 그대로 가져온 내용입니다.\n\n")
	fmt.Fprintf(&b, "- 이미지: `%s` → `%s`\n", shortDigest(oldImg.Digest), shortDigest(newImg.Digest))
	fmt.Fprintf(&b, "- 코드 커밋: `%.8s` → `%.8s`\n", oldImg.Commit, newImg.Commit)
	fmt.Fprintf(&b, "- 그 사이의 커밋 %d개\n", len(cmp.Commits))
	for _, cm := range cmp.Commits {
		fmt.Fprintf(&b, "  - `%s` %s (%s)\n", cm.ShortID, cm.Title, cm.AuthorName)
	}
	fmt.Fprintf(&b, "- 이 이미지와 관련된 파일 %d개 변경, 관련 없는 파일 %d개는 제외\n", len(files), skipped)
	for _, f := range files {
		fmt.Fprintf(&b, "  - `%s`\n", f)
	}
	b.WriteString("\n")

	if len(files) == 0 {
		b.WriteString("## 해석\n\n서비스 코드와 CI 정의에 바뀐 것이 없어 요약할 내용이 없습니다.\n")
		return b.String(), nil
	}

	// 해석 부분: 실패해도 요청서의 사실 부분은 남긴다. 요약은 배포를 막는 장치가 아니다.
	b.WriteString("## 해석\n\n")
	s, err := summarizeDiff(ctx, ai, model, diff.String())
	if err != nil {
		fmt.Fprintf(&b, "요약을 만들지 못했습니다: %v\n\n변경 내용을 직접 확인해야 합니다.\n", err)
		return b.String(), nil
	}
	b.WriteString(strings.TrimPrefix(s.markdown(model), "## 변경 요약\n\n"))
	return b.String(), nil
}

func shortDigest(d string) string {
	if len(d) > 19 {
		return d[:19]
	}
	return d
}

func runDeployRequest(args []string) int {
	fs := flag.NewFlagSet("deploy-request", flag.ExitOnError)
	base := fs.String("gitlab", "https://gitlab.com/api/v4", "GitLab API 주소")
	registry := fs.String("registry", "registry.gitlab.com", "이미지 저장소 호스트")
	baseRef := fs.String("base", "origin/main", "지금 배포된 상태를 가리키는 git 참조")
	out := fs.String("out", "deploy-request.md", "요청서를 쓸 파일")
	fs.Parse(args)
	if fs.NArg() == 0 {
		usage()
	}
	c := newClient(*base, os.Getenv("GITLAB_TOKEN"))
	ai := anthropic.NewClient()

	var all strings.Builder
	for _, valuesPath := range fs.Args() {
		doc, err := buildDeployRequest(context.Background(), c, ai, modelFromEnv(), *registry, *baseRef, valuesPath)
		if err != nil {
			fmt.Printf("요청서 생성 실패 %s\n  %v\n", valuesPath, err)
			return 1
		}
		all.WriteString(doc + "\n")
	}
	fmt.Print(all.String())
	if err := os.WriteFile(*out, []byte(all.String()), 0o644); err != nil {
		fmt.Println(err)
		return 1
	}
	return 0
}
