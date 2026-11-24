package main

import (
	"fmt"
	"net/url"
	"path"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// evidence는 배포 한 건을 서비스 하나 기준으로 끝에서 끝까지 이은 기록이다.
// 돌고 있는 이미지 → 그 값을 넣은 변경 요청과 승인자 → 이미지를 만든 코드 커밋과 검사 결과.
type evidence struct {
	Service      string    `json:"service"`
	Image        string    `json:"image"`
	DeployCommit string    `json:"deploy_commit"` // 배포 설정 저장소에서 ArgoCD가 읽은 커밋
	RecordedAt   time.Time `json:"recorded_at"`

	Change struct {
		MergeRequest   string    `json:"merge_request"`
		Author         string    `json:"author"`
		Approver       string    `json:"approver"`
		ApprovedAt     time.Time `json:"approved_at"`
		ApprovedCommit string    `json:"approved_commit"`
	} `json:"change"`

	Code struct {
		Commit   string `json:"commit"`
		Pipeline string `json:"pipeline"`
	} `json:"code"`
}

// buildEvidence는 배포 설정 저장소의 한 커밋에서 값 파일 하나를 읽어 기록을 만든다.
// 중간에 하나라도 확인되지 않으면 기록을 만들지 않고 실패한다. 빈칸이 있는 증적은 증적이 아니다.
func buildEvidence(c *client, registry, project, deployCommit, valuesPath string) (*evidence, error) {
	base := projectPath(project)

	// 1. 그 커밋 시점의 값 파일을 GitLab에서 읽는다. 내 컴퓨터의 파일이 아니라 저장소에 기록된 것을 본다.
	raw, err := c.get(base+"/repository/files/"+url.PathEscape(valuesPath)+"/raw", url.Values{"ref": {deployCommit}})
	if err != nil {
		return nil, err
	}
	var v struct {
		Image imageRef `yaml:"image"`
	}
	if err := yaml.Unmarshal(raw, &v); err != nil {
		return nil, err
	}

	// 2. 이미지 → 코드 커밋과 검사 결과
	img, err := verifyImage(c, registry, v.Image)
	if err != nil {
		return nil, err
	}

	// 3. 이 값 파일을 마지막으로 바꾼 커밋을 찾고, 그 커밋을 main에 넣은 변경 요청을 찾는다.
	var commits []commit
	q := url.Values{"ref_name": {deployCommit}, "path": {valuesPath}, "per_page": {"1"}}
	if err := c.getJSON(base+"/repository/commits", q, &commits); err != nil {
		return nil, err
	}
	if len(commits) == 0 {
		return nil, fmt.Errorf("%s를 바꾼 커밋을 찾지 못했습니다", valuesPath)
	}
	var mrs []mergeRequest
	if err := c.getJSON(base+"/repository/commits/"+commits[0].ID+"/merge_requests", nil, &mrs); err != nil {
		return nil, err
	}
	mergeCommit := ""
	for _, mr := range mrs {
		if mr.State == "merged" && mr.TargetBranch == "main" {
			mergeCommit = mr.MergeCommitSHA
			break
		}
	}
	if mergeCommit == "" {
		return nil, fmt.Errorf("%s를 바꾼 커밋 %s이 변경 요청으로 들어오지 않았습니다", valuesPath, commits[0].ID)
	}

	// 4. 변경 요청 → 올린 사람과 승인자
	ap, err := verifyApproval(c, project, mergeCommit)
	if err != nil {
		return nil, err
	}

	e := &evidence{
		Service:      strings.TrimSuffix(path.Base(valuesPath), ".yaml"),
		Image:        v.Image.Repository + "@" + v.Image.Digest,
		DeployCommit: deployCommit,
		RecordedAt:   time.Now().UTC().Truncate(time.Second),
	}
	e.Change.MergeRequest = ap.MergeRequestURL
	e.Change.Author = ap.Author
	e.Change.Approver = ap.Approver
	e.Change.ApprovedAt = ap.ApprovedAt
	e.Change.ApprovedCommit = ap.ApprovedCommit
	e.Code.Commit = v.Image.Commit
	e.Code.Pipeline = img.PipelineURL
	return e, nil
}
