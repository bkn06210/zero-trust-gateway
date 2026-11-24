package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// client는 GitLab API를 읽기만 한다. 이 도구는 GitLab의 어떤 것도 바꾸지 않는다.
type client struct {
	base  string // 예: https://gitlab.com/api/v4
	token string // 비어 있으면 로그인 없이 읽는다. 공개 프로젝트는 그걸로 충분하다.
	http  *http.Client
}

func newClient(base, token string) *client {
	return &client{base: base, token: token, http: &http.Client{Timeout: 20 * time.Second}}
}

// projectPath는 "그룹/프로젝트"를 API 주소에 넣을 수 있는 모양으로 바꾼다. 슬래시가 %2F가 된다.
func projectPath(project string) string {
	return "/projects/" + url.PathEscape(project)
}

func (c *client) get(path string, query url.Values) ([]byte, error) {
	u := c.base + path
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	req, err := http.NewRequest(http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	if c.token != "" {
		req.Header.Set("PRIVATE-TOKEN", c.token)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GET %s: HTTP %d", path, resp.StatusCode)
	}
	return body, nil
}

func (c *client) getJSON(path string, query url.Values, out any) error {
	body, err := c.get(path, query)
	if err != nil {
		return err
	}
	return json.Unmarshal(body, out)
}

type pipeline struct {
	ID     int64  `json:"id"`
	SHA    string `json:"sha"`
	Ref    string `json:"ref"`
	Status string `json:"status"`
	WebURL string `json:"web_url"`
}

type job struct {
	ID     int64  `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"`
}
