package main

import (
	"fmt"
	"os"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"
)

// expectedSettings는 "설정이 이래야 한다"를 적어 둔 파일의 모양이다.
// 이 파일은 배포 설정 저장소에 있고, 바꾸려면 변경 요청과 승인을 거쳐야 한다.
type expectedSettings struct {
	ProtectedBranches map[string]branchRule `yaml:"protected_branches"`
}

type branchRule struct {
	Push           []string `yaml:"push"`
	Merge          []string `yaml:"merge"`
	AllowForcePush bool     `yaml:"allow_force_push"`
}

type accessLevel struct {
	AccessLevel int    `json:"access_level"`
	Description string `json:"access_level_description"`
	DeployKeyID *int64 `json:"deploy_key_id"`
}

type protectedBranch struct {
	Name           string        `json:"name"`
	Push           []accessLevel `json:"push_access_levels"`
	Merge          []accessLevel `json:"merge_access_levels"`
	AllowForcePush bool          `json:"allow_force_push"`
}

// levelName은 GitLab이 숫자로 주는 권한을 파일에 적는 이름으로 바꾼다.
func levelName(a accessLevel) string {
	if a.DeployKeyID != nil {
		return "deploy_key:" + a.Description
	}
	switch a.AccessLevel {
	case 0:
		return "no_one"
	case 30:
		return "developers"
	case 40:
		return "maintainers"
	case 60:
		return "admins"
	}
	return fmt.Sprintf("level_%d", a.AccessLevel)
}

func names(levels []accessLevel) []string {
	out := make([]string, 0, len(levels))
	for _, a := range levels {
		out = append(out, levelName(a))
	}
	sort.Strings(out)
	return out
}

func sorted(in []string) []string {
	out := append([]string(nil), in...)
	sort.Strings(out)
	return out
}

// verifySettings는 실제 브랜치 보호 설정이 파일과 같은지 본다. 다른 점을 전부 모아서 돌려준다.
// 검사가 아무리 정확해도 누군가 보호를 풀어 버리면 소용없다. 그래서 설정 자체가 그대로인지를 따로 확인한다.
func verifySettings(c *client, project, expectPath string) ([]string, error) {
	data, err := os.ReadFile(expectPath)
	if err != nil {
		return nil, err
	}
	var want expectedSettings
	if err := yaml.Unmarshal(data, &want); err != nil {
		return nil, fmt.Errorf("%s: %w", expectPath, err)
	}
	if len(want.ProtectedBranches) == 0 {
		return nil, fmt.Errorf("%s에 protected_branches가 없습니다", expectPath)
	}

	var actual []protectedBranch
	if err := c.getJSON(projectPath(project)+"/protected_branches", nil, &actual); err != nil {
		return nil, err
	}
	byName := map[string]protectedBranch{}
	for _, b := range actual {
		byName[b.Name] = b
	}

	var diffs []string
	branches := make([]string, 0, len(want.ProtectedBranches))
	for name := range want.ProtectedBranches {
		branches = append(branches, name)
	}
	sort.Strings(branches)
	for _, name := range branches {
		w := want.ProtectedBranches[name]
		a, found := byName[name]
		if !found {
			diffs = append(diffs, fmt.Sprintf("%s: 보호가 걸려 있지 않습니다", name))
			continue
		}
		if got, exp := strings.Join(names(a.Push), ", "), strings.Join(sorted(w.Push), ", "); got != exp {
			diffs = append(diffs, fmt.Sprintf("%s: push 허용 대상이 다릅니다. 기대 [%s] 실제 [%s]", name, exp, got))
		}
		if got, exp := strings.Join(names(a.Merge), ", "), strings.Join(sorted(w.Merge), ", "); got != exp {
			diffs = append(diffs, fmt.Sprintf("%s: 합치기 허용 대상이 다릅니다. 기대 [%s] 실제 [%s]", name, exp, got))
		}
		if a.AllowForcePush != w.AllowForcePush {
			diffs = append(diffs, fmt.Sprintf("%s: 강제 push 허용이 다릅니다. 기대 %v 실제 %v", name, w.AllowForcePush, a.AllowForcePush))
		}
	}
	return diffs, nil
}
