package main

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

// imageRef는 값 파일에서 이 도구가 읽는 세 줄이다.
type imageRef struct {
	Repository string `yaml:"repository"`
	Digest     string `yaml:"digest"`
	Commit     string `yaml:"commit"`
}

func readImageRef(path string) (imageRef, error) {
	var v struct {
		Image imageRef `yaml:"image"`
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return imageRef{}, err
	}
	if err := yaml.Unmarshal(data, &v); err != nil {
		return imageRef{}, fmt.Errorf("%s: %w", path, err)
	}
	return v.Image, nil
}
