package main

import (
	"fmt"
	"net/url"
	"time"
)

type user struct {
	Username string `json:"username"`
}

type commit struct {
	ID        string   `json:"id"`
	ParentIDs []string `json:"parent_ids"`
}

type mergeRequest struct {
	IID             int64   `json:"iid"`
	State           string  `json:"state"`
	TargetBranch    string  `json:"target_branch"`
	SHA             string  `json:"sha"`               // 합칠 때 변경 요청 브랜치의 마지막 커밋
	MergeCommitSHA  string  `json:"merge_commit_sha"`  // main에 생긴 병합 커밋
	SquashCommitSHA *string `json:"squash_commit_sha"` // 커밋을 하나로 뭉쳤으면 그 새 커밋
	Author          user    `json:"author"`
	WebURL          string  `json:"web_url"`
}

type approvals struct {
	ApprovedBy []struct {
		User       user      `json:"user"`
		ApprovedAt time.Time `json:"approved_at"`
	} `json:"approved_by"`
}

type mrPipeline struct {
	ID        int64     `json:"id"`
	SHA       string    `json:"sha"`
	Ref       string    `json:"ref"`
	Source    string    `json:"source"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"created_at"`
}

// approvalEvidence는 통과했을 때 남기는 근거다.
type approvalEvidence struct {
	MergeRequestURL string
	Author          string
	Approver        string
	ApprovedAt      time.Time
	ApprovedCommit  string
}

// verifyApproval은 main의 커밋 하나가 "다른 사람이 승인한 변경 요청으로, 승인받은 내용 그대로" 들어왔는지 확인한다.
func verifyApproval(c *client, project, sha string) (*approvalEvidence, error) {
	base := projectPath(project)

	// 1. 이 커밋을 main에 넣은 변경 요청을 찾는다. 없으면 변경 요청을 거치지 않고 들어온 커밋이다.
	var mrs []mergeRequest
	if err := c.getJSON(base+"/repository/commits/"+sha+"/merge_requests", nil, &mrs); err != nil {
		return nil, err
	}
	var mr *mergeRequest
	for i := range mrs {
		if mrs[i].MergeCommitSHA == sha && mrs[i].State == "merged" && mrs[i].TargetBranch == "main" {
			mr = &mrs[i]
			break
		}
	}
	if mr == nil {
		return nil, fmt.Errorf("이 커밋을 main에 합친 변경 요청이 없습니다")
	}

	// 2. 승인받은 커밋이 그대로 들어왔는가.
	//    병합 커밋의 두 번째 부모가 변경 요청의 마지막 커밋이어야 한다. 뭉치기를 쓰면 새 커밋이 생겨 대조할 수 없으므로 받지 않는다.
	if mr.SquashCommitSHA != nil {
		return nil, fmt.Errorf("변경 요청 %d: 커밋을 뭉쳐서 합쳤습니다. 승인받은 커밋과 대조할 수 없습니다", mr.IID)
	}
	var merged commit
	if err := c.getJSON(base+"/repository/commits/"+sha, nil, &merged); err != nil {
		return nil, err
	}
	if len(merged.ParentIDs) != 2 || merged.ParentIDs[1] != mr.SHA {
		return nil, fmt.Errorf("변경 요청 %d: 병합 커밋의 부모가 변경 요청의 마지막 커밋 %s이 아닙니다", mr.IID, mr.SHA)
	}

	// 3. 그 마지막 커밋이 변경 요청에 올라온 시각을 구한다.
	//    커밋에 적힌 날짜는 올린 사람이 마음대로 쓸 수 있어서 믿지 않는다.
	//    대신 GitLab이 그 커밋에 대해 변경 요청 파이프라인을 만든 시각을 쓴다. 서버가 찍은 시각이다.
	//    같은 커밋을 다른 변경 요청에 먼저 올려 두는 수법을 막으려고 이 변경 요청의 파이프라인만 본다.
	var pipelines []mrPipeline
	q := url.Values{"sha": {mr.SHA}, "per_page": {"50"}}
	if err := c.getJSON(base+"/pipelines", q, &pipelines); err != nil {
		return nil, err
	}
	wantRef := fmt.Sprintf("refs/merge-requests/%d/head", mr.IID)
	var pushedAt time.Time
	checked := false
	for _, p := range pipelines {
		if p.SHA != mr.SHA || p.Ref != wantRef || p.Source != "merge_request_event" {
			continue
		}
		if pushedAt.IsZero() || p.CreatedAt.Before(pushedAt) {
			pushedAt = p.CreatedAt
		}
		if p.Status == "success" {
			checked = true
		}
	}
	if pushedAt.IsZero() {
		return nil, fmt.Errorf("변경 요청 %d: 마지막 커밋 %s에 대해 돈 검사 파이프라인이 없습니다", mr.IID, mr.SHA)
	}
	if !checked {
		return nil, fmt.Errorf("변경 요청 %d: 마지막 커밋 %s의 검사 파이프라인이 성공하지 않았습니다", mr.IID, mr.SHA)
	}

	// 4. 올린 사람이 아닌 누군가가, 마지막 커밋이 올라온 뒤에 승인했는가.
	//    승인 뒤에 커밋을 더 얹으면 마지막 커밋이 승인보다 늦어져 여기서 걸린다.
	var ap approvals
	if err := c.getJSON(fmt.Sprintf("%s/merge_requests/%d/approvals", base, mr.IID), nil, &ap); err != nil {
		return nil, err
	}
	if len(ap.ApprovedBy) == 0 {
		return nil, fmt.Errorf("변경 요청 %d: 승인이 없습니다", mr.IID)
	}
	reason := "승인한 사람이 올린 사람 본인뿐입니다"
	for _, a := range ap.ApprovedBy {
		if a.User.Username == mr.Author.Username {
			continue
		}
		if !a.ApprovedAt.After(pushedAt) {
			reason = fmt.Sprintf("%s의 승인(%s) 뒤에 커밋이 올라왔습니다(%s)", a.User.Username, a.ApprovedAt.Format(time.RFC3339), pushedAt.Format(time.RFC3339))
			continue
		}
		return &approvalEvidence{
			MergeRequestURL: mr.WebURL,
			Author:          mr.Author.Username,
			Approver:        a.User.Username,
			ApprovedAt:      a.ApprovedAt,
			ApprovedCommit:  mr.SHA,
		}, nil
	}
	return nil, fmt.Errorf("변경 요청 %d: %s", mr.IID, reason)
}
