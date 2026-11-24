// deployctl은 배포 설정 저장소의 CI에서 실행하는 검사 도구다.
// 실패하면 종료 코드 1로 끝나서 CI 작업이 실패한다.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	switch os.Args[1] {
	case "verify-image":
		os.Exit(runVerifyImage(os.Args[2:]))
	case "verify-approval":
		os.Exit(runVerifyApproval(os.Args[2:]))
	case "record":
		os.Exit(runRecord(os.Args[2:]))
	case "trace":
		os.Exit(runTrace(os.Args[2:]))
	default:
		usage()
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "사용법:")
	fmt.Fprintln(os.Stderr, "  deployctl verify-image [-gitlab 주소] 값파일...")
	fmt.Fprintln(os.Stderr, "  deployctl verify-approval [-gitlab 주소] -project 그룹/프로젝트 커밋...")
	fmt.Fprintln(os.Stderr, "  deployctl record -project 그룹/프로젝트 -commit 커밋 [-out 파일] 값파일...")
	fmt.Fprintln(os.Stderr, "  deployctl trace -project 그룹/프로젝트 -commit 커밋 -service 이름 -image 저장소@다이제스트")
	os.Exit(2)
}

func runVerifyImage(args []string) int {
	fs := flag.NewFlagSet("verify-image", flag.ExitOnError)
	base := fs.String("gitlab", "https://gitlab.com/api/v4", "GitLab API 주소")
	registry := fs.String("registry", "registry.gitlab.com", "이미지 저장소 호스트")
	fs.Parse(args)
	if fs.NArg() == 0 {
		usage()
	}
	c := newClient(*base, os.Getenv("GITLAB_TOKEN"))

	failed := false
	for _, path := range fs.Args() {
		img, err := readImageRef(path)
		if err == nil {
			var ev *imageEvidence
			ev, err = verifyImage(c, *registry, img)
			if err == nil {
				fmt.Printf("통과 %s\n  이미지 %s@%s\n  코드 커밋 %s\n  파이프라인 %s\n", path, img.Repository, img.Digest, img.Commit, ev.PipelineURL)
				continue
			}
		}
		failed = true
		fmt.Printf("실패 %s\n  %s\n", path, strings.ReplaceAll(err.Error(), "\n", "\n  "))
	}
	if failed {
		return 1
	}
	return 0
}

func runVerifyApproval(args []string) int {
	fs := flag.NewFlagSet("verify-approval", flag.ExitOnError)
	base := fs.String("gitlab", "https://gitlab.com/api/v4", "GitLab API 주소")
	project := fs.String("project", "", "배포 설정 프로젝트. 예: 그룹/프로젝트")
	fs.Parse(args)
	if *project == "" || fs.NArg() == 0 {
		usage()
	}
	c := newClient(*base, os.Getenv("GITLAB_TOKEN"))

	// 넘겨받은 커밋을 전부 본다. 하나라도 실패하면 전체가 실패다.
	// 마지막 커밋만 보면, 승인 없이 들어온 앞 커밋이 뒤의 정상 커밋에 묻어서 배포된다.
	failed := false
	for _, sha := range fs.Args() {
		ev, err := verifyApproval(c, *project, sha)
		if err != nil {
			failed = true
			fmt.Printf("실패 %s\n  %s\n", sha, strings.ReplaceAll(err.Error(), "\n", "\n  "))
			continue
		}
		fmt.Printf("통과 %s\n  변경 요청 %s\n  올린 사람 %s\n  승인한 사람 %s (%s)\n  승인받은 커밋 %s\n", sha, ev.MergeRequestURL, ev.Author, ev.Approver, ev.ApprovedAt.Format("2006-01-02T15:04:05Z07:00"), ev.ApprovedCommit)
	}
	if failed {
		return 1
	}
	return 0
}

// record는 배포 직후 CI에서 실행한다. 서비스마다 증적을 만들어 JSON 파일 하나로 남긴다.
func runRecord(args []string) int {
	fs := flag.NewFlagSet("record", flag.ExitOnError)
	base := fs.String("gitlab", "https://gitlab.com/api/v4", "GitLab API 주소")
	registry := fs.String("registry", "registry.gitlab.com", "이미지 저장소 호스트")
	project := fs.String("project", "", "배포 설정 프로젝트")
	commitSHA := fs.String("commit", "", "배포 설정 저장소의 커밋")
	out := fs.String("out", "evidence.json", "기록을 쓸 파일")
	fs.Parse(args)
	if *project == "" || *commitSHA == "" || fs.NArg() == 0 {
		usage()
	}
	c := newClient(*base, os.Getenv("GITLAB_TOKEN"))

	var records []*evidence
	for _, valuesPath := range fs.Args() {
		e, err := buildEvidence(c, *registry, *project, *commitSHA, valuesPath)
		if err != nil {
			fmt.Printf("실패 %s\n  %s\n", valuesPath, strings.ReplaceAll(err.Error(), "\n", "\n  "))
			return 1
		}
		records = append(records, e)
	}
	data, err := json.MarshalIndent(records, "", "  ")
	if err != nil {
		fmt.Println(err)
		return 1
	}
	if err := os.WriteFile(*out, append(data, '\n'), 0o644); err != nil {
		fmt.Println(err)
		return 1
	}
	fmt.Printf("%s\n기록 %d건을 %s에 썼습니다\n", data, len(records), *out)
	return 0
}

// trace는 사람이 실행한다. 지금 돌고 있는 이미지에서 출발해 승인자와 코드 커밋까지 거꾸로 간다.
func runTrace(args []string) int {
	fs := flag.NewFlagSet("trace", flag.ExitOnError)
	base := fs.String("gitlab", "https://gitlab.com/api/v4", "GitLab API 주소")
	registry := fs.String("registry", "registry.gitlab.com", "이미지 저장소 호스트")
	project := fs.String("project", "", "배포 설정 프로젝트")
	commitSHA := fs.String("commit", "", "ArgoCD가 읽은 배포 설정 커밋")
	service := fs.String("service", "", "서비스 이름")
	running := fs.String("image", "", "클러스터에서 실제로 돌고 있는 이미지. 저장소@다이제스트")
	fs.Parse(args)
	if *project == "" || *commitSHA == "" || *service == "" || *running == "" {
		usage()
	}
	c := newClient(*base, os.Getenv("GITLAB_TOKEN"))

	e, err := buildEvidence(c, *registry, *project, *commitSHA, "values/"+*service+".yaml")
	if err != nil {
		fmt.Printf("추적 실패\n  %s\n", strings.ReplaceAll(err.Error(), "\n", "\n  "))
		return 1
	}
	// 클러스터에서 도는 것과 저장소에 적힌 것이 다르면 승인 경로 밖에서 바뀐 것이다.
	if e.Image != *running {
		fmt.Printf("불일치\n  돌고 있는 이미지 %s\n  저장소에 적힌 이미지 %s\n", *running, e.Image)
		return 1
	}
	fmt.Printf("서비스          %s\n", e.Service)
	fmt.Printf("돌고 있는 이미지 %s\n", e.Image)
	fmt.Printf("배포 설정 커밋   %s\n", e.DeployCommit)
	fmt.Printf("변경 요청        %s\n", e.Change.MergeRequest)
	fmt.Printf("올린 사람        %s\n", e.Change.Author)
	fmt.Printf("승인한 사람      %s (%s)\n", e.Change.Approver, e.Change.ApprovedAt.Format(time.RFC3339))
	fmt.Printf("승인받은 커밋    %s\n", e.Change.ApprovedCommit)
	fmt.Printf("코드 커밋        %s\n", e.Code.Commit)
	fmt.Printf("검사 파이프라인  %s\n", e.Code.Pipeline)
	return 0
}
