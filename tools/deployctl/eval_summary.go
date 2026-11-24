package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/anthropics/anthropic-sdk-go/v2"
	"gopkg.in/yaml.v3"
)

type evalCase struct {
	Diff      string `yaml:"diff" json:"diff"`
	Expect    string `yaml:"expect" json:"expect"`
	File      string `yaml:"file" json:"file,omitempty"`
	Source    string `yaml:"source" json:"source"`
	Condition string `yaml:"condition" json:"condition"`
	Note      string `yaml:"note" json:"note"`
}

type evalRun struct {
	Case    evalCase       `json:"case"`
	Run     int            `json:"run"`
	Correct bool           `json:"correct"`
	Error   string         `json:"error,omitempty"`
	Output  *changeSummary `json:"output,omitempty"`
}

// grade는 모델의 답을 정답과 비교한다. 사람이나 모델의 판단이 끼지 않는다.
//   - 위험한 문제: 정해 둔 분류와 파일을 함께 짚은 항목이 하나라도 있으면 맞힌 것.
//   - 멀쩡한 문제: 아무것도 짚지 않아야 맞힌 것. 하나라도 짚으면 헛경고.
func grade(c evalCase, out *changeSummary) bool {
	if c.Expect == "none" {
		return len(out.Risks) == 0
	}
	for _, r := range out.Risks {
		if r.Category == c.Expect && strings.Contains(r.File, c.File) {
			return true
		}
	}
	return false
}

func runEvalSummary(args []string) int {
	fs := flag.NewFlagSet("eval-summary", flag.ExitOnError)
	dir := fs.String("cases", "eval/cases", "문제 폴더")
	runs := fs.Int("runs", 1, "문제마다 반복할 횟수")
	out := fs.String("out", "", "전체 결과를 쓸 JSON 파일")
	fs.Parse(args)

	data, err := os.ReadFile(filepath.Join(*dir, "cases.yaml"))
	if err != nil {
		fmt.Println(err)
		return 1
	}
	var spec struct {
		Cases []evalCase `yaml:"cases"`
	}
	if err := yaml.Unmarshal(data, &spec); err != nil {
		fmt.Println(err)
		return 1
	}

	client := anthropic.NewClient()
	model := modelFromEnv()
	results := make([]evalRun, len(spec.Cases)**runs)

	// 한 번에 네 개까지만 보낸다.
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup
	for i, c := range spec.Cases {
		diff, err := os.ReadFile(filepath.Join(*dir, c.Diff))
		for n := 0; n < *runs; n++ {
			idx := i**runs + n
			results[idx] = evalRun{Case: c, Run: n + 1}
			if err != nil {
				results[idx].Error = err.Error()
				continue
			}
			wg.Add(1)
			go func() {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				s, err := summarizeDiff(context.Background(), client, model, string(diff))
				if err != nil {
					results[idx].Error = err.Error()
					return
				}
				results[idx].Output = s
				results[idx].Correct = grade(c, s)
			}()
		}
	}
	wg.Wait()

	fmt.Printf("모델 %s, 문제 %d개, 문제당 %d회\n\n", model, len(spec.Cases), *runs)
	for i, c := range spec.Cases {
		correct, failed := 0, 0
		var flagged []string
		for n := 0; n < *runs; n++ {
			r := results[i**runs+n]
			switch {
			case r.Error != "":
				failed++
			case r.Correct:
				correct++
			}
			if r.Output != nil {
				var cats []string
				for _, k := range r.Output.Risks {
					cats = append(cats, k.Category)
				}
				flagged = append(flagged, "["+strings.Join(cats, ",")+"]")
			}
		}
		fmt.Printf("%-40s 정답 %-24s 맞힘 %d/%d", c.Diff, c.Expect, correct, *runs)
		if failed > 0 {
			fmt.Printf(" 오류 %d", failed)
		}
		fmt.Printf("\n    조건: %s, 출처: %s\n    짚은 것: %s\n", c.Condition, c.Source, strings.Join(flagged, " "))
	}
	for _, r := range results {
		if r.Error != "" {
			fmt.Printf("\n오류 %s #%d: %s\n", r.Case.Diff, r.Run, r.Error)
		}
	}

	if *out != "" {
		b, _ := json.MarshalIndent(map[string]any{"model": model, "runs": *runs, "results": results}, "", "  ")
		if err := os.WriteFile(*out, append(b, '\n'), 0o644); err != nil {
			fmt.Println(err)
			return 1
		}
	}
	return 0
}
