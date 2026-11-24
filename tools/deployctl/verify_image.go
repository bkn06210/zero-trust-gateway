package main

import (
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"strings"
)

var (
	digestPattern = regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)
	commitPattern = regexp.MustCompile(`^[0-9a-f]{40}$`)
)

// imageEvidence는 통과했을 때 남기는 근거다. 나중에 증적 기록에 그대로 들어간다.
type imageEvidence struct {
	PipelineID  int64
	PipelineURL string
	BuildJobID  int64
}

// requiredJobs는 이 서비스의 이미지가 배포되려면 성공해 있어야 하는 작업 이름이다.
// 이름으로 하나씩 찾는 이유: 파이프라인 전체가 성공이어도 검사 작업이 지워졌으면 통과시키지 않기 위해서다.
func requiredJobs(service string) []string {
	return []string{
		"test: [" + service + "]",
		"semgrep",
		"gitleaks",
		"build-image: [" + service + "]",
		"scan-image: [" + service + "]",
	}
}

// verifyImage는 값 파일에 적힌 이미지가 "코드 저장소 main의 그 커밋에서 만들어지고 검사를 통과한 이미지"인지 확인한다.
// 값 파일에 적힌 커밋은 파이프라인을 찾는 단서일 뿐이다. 믿는 것은 파이프라인 기록이다.
func verifyImage(c *client, registry string, img imageRef) (*imageEvidence, error) {
	// 1. 형식부터 본다. 짧은 커밋 번호나 태그는 받지 않는다. 여러 대상을 가리킬 수 있기 때문이다.
	if !digestPattern.MatchString(img.Digest) {
		return nil, fmt.Errorf("image.digest 형식이 아닙니다: %q", img.Digest)
	}
	if !commitPattern.MatchString(img.Commit) {
		return nil, fmt.Errorf("image.commit은 40자 커밋 번호여야 합니다: %q", img.Commit)
	}

	// 2. 이미지 주소에서 코드 프로젝트와 서비스 이름을 꺼낸다.
	//    registry.gitlab.com/그룹/프로젝트/서비스 → 프로젝트 "그룹/프로젝트", 서비스 "서비스"
	rest, ok := strings.CutPrefix(img.Repository, registry+"/")
	i := strings.LastIndex(rest, "/")
	if !ok || i <= 0 || i == len(rest)-1 {
		return nil, fmt.Errorf("image.repository가 %s/그룹/프로젝트/서비스 모양이 아닙니다: %q", registry, img.Repository)
	}
	project, service := rest[:i], rest[i+1:]

	// 3. 그 커밋으로 main에서 돈 파이프라인을 찾는다. main이 아닌 브랜치에서 만든 이미지는 대상이 아니다.
	var pipelines []pipeline
	q := url.Values{"sha": {img.Commit}, "ref": {"main"}, "per_page": {"20"}}
	if err := c.getJSON(projectPath(project)+"/pipelines", q, &pipelines); err != nil {
		return nil, err
	}
	if len(pipelines) == 0 {
		return nil, fmt.Errorf("%s의 main에 커밋 %s의 파이프라인이 없습니다", project, img.Commit)
	}

	// 4. 같은 커밋으로 파이프라인이 여러 번 돌았을 수 있다. 하나라도 조건을 전부 만족하면 통과다.
	want := img.Repository + "@" + img.Digest
	var reasons []error
	for _, p := range pipelines {
		ev, err := checkPipeline(c, project, service, p, want)
		if err == nil {
			return ev, nil
		}
		reasons = append(reasons, fmt.Errorf("파이프라인 %d: %w", p.ID, err))
	}
	return nil, errors.Join(reasons...)
}

func checkPipeline(c *client, project, service string, p pipeline, want string) (*imageEvidence, error) {
	// 물어본 대로 답이 왔는지 다시 확인한다. 필터가 무시되면 엉뚱한 파이프라인을 믿게 된다.
	if p.Ref != "main" {
		return nil, fmt.Errorf("main이 아닌 %q에서 돌았습니다", p.Ref)
	}
	if p.Status != "success" {
		return nil, fmt.Errorf("상태가 %q입니다", p.Status)
	}

	var jobs []job
	q := url.Values{"per_page": {"100"}}
	if err := c.getJSON(fmt.Sprintf("%s/pipelines/%d/jobs", projectPath(project), p.ID), q, &jobs); err != nil {
		return nil, err
	}
	byName := map[string]job{}
	for _, j := range jobs {
		byName[j.Name] = j
	}

	// 필요한 작업이 전부 있고 전부 성공인가. 없는 것도 실패로 본다.
	for _, name := range requiredJobs(service) {
		j, found := byName[name]
		if !found {
			return nil, fmt.Errorf("작업 %q이 없습니다", name)
		}
		if j.Status != "success" {
			return nil, fmt.Errorf("작업 %q의 상태가 %q입니다", name, j.Status)
		}
	}

	// 빌드 작업이 남긴 다이제스트 기록을 읽어 값 파일의 것과 글자 그대로 비교한다.
	build := byName["build-image: ["+service+"]"]
	path := fmt.Sprintf("%s/jobs/%d/artifacts/digests/%s.txt", projectPath(project), build.ID, service)
	body, err := c.get(path, nil)
	if err != nil {
		return nil, fmt.Errorf("다이제스트 기록을 읽지 못했습니다: %w", err)
	}
	got := strings.TrimSpace(string(body))
	if got != want {
		return nil, fmt.Errorf("다이제스트가 다릅니다. 파이프라인 기록 %s", got)
	}
	return &imageEvidence{PipelineID: p.ID, PipelineURL: p.WebURL, BuildJobID: build.ID}, nil
}
