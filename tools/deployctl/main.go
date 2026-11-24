// deployctl은 배포 설정 저장소의 CI에서 실행하는 검사 도구다.
// 실패하면 종료 코드 1로 끝나서 CI 작업이 실패한다.
package main

import (
	"flag"
	"fmt"
	"os"
	"strings"
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
	default:
		usage()
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "사용법:")
	fmt.Fprintln(os.Stderr, "  deployctl verify-image [-gitlab 주소] 값파일...")
	fmt.Fprintln(os.Stderr, "  deployctl verify-approval [-gitlab 주소] -project 그룹/프로젝트 커밋...")
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
