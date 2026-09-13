package main

import (
	"fmt"
	"regexp"
	"strings"
)

const maxFilePatternLength = 512

func compileFilePattern(value string) (*regexp.Regexp, error) {
	expression := strings.TrimSpace(value)
	if len(expression) > maxFilePatternLength {
		return nil, fmt.Errorf("fileMustInclude is too long")
	}
	if strings.HasPrefix(expression, "/") {
		if closing := strings.LastIndex(expression, "/"); closing > 0 {
			flags := expression[closing+1:]
			for _, flag := range flags {
				if flag != 'i' && flag != 'm' {
					return nil, fmt.Errorf("unsupported fileMustInclude flags")
				}
			}
			prefix := ""
			if strings.ContainsRune(flags, 'i') {
				prefix += "(?i)"
			}
			if strings.ContainsRune(flags, 'm') {
				prefix += "(?m)"
			}
			expression = prefix + expression[1:closing]
		}
	}
	matcher, err := regexp.Compile(expression)
	if err != nil {
		return nil, fmt.Errorf("invalid fileMustInclude pattern")
	}
	return matcher, nil
}
