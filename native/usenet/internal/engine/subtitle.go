package engine

import (
	"context"
	"fmt"
	"net/http"
	"path"
	"strconv"
	"strings"
	"time"
)

func isSubtitle(name string) bool {
	switch strings.ToLower(path.Ext(name)) {
	case ".srt", ".ass", ".ssa", ".vtt", ".sub":
		return true
	default:
		return false
	}
}
func standaloneSubtitles(files []*File, selection Selection) []*File {
	var result []*File
	episodeMatch, _ := (Selection{Season: selection.Season, Episode: selection.Episode}).matcher()
	for _, f := range files {
		ext := strings.ToLower(path.Ext(f.Name))
		if ext != ".srt" && ext != ".ass" && ext != ".ssa" && ext != ".vtt" && ext != ".sub" {
			continue
		}
		matched, _ := episodeMatch(f.Name, f.Index)
		if selection.Episode > 0 && !matched {
			continue
		}
		result = append(result, f)
		if len(result) == 128 {
			break
		}
	}
	return result
}
func subtitleManifest(id string, files []*File) []map[string]string {
	out := make([]map[string]string, 0, len(files))
	for i, f := range files {
		stem := strings.TrimSuffix(path.Base(f.Name), path.Ext(f.Name))
		lang := "und"
		if j := strings.LastIndexAny(stem, "._-"); j >= 0 {
			candidate := strings.ToLower(stem[j+1:])
			if len(candidate) == 2 || len(candidate) == 3 {
				lang = candidate
			}
		}
		out = append(out, map[string]string{"id": strconv.Itoa(i), "filename": path.Base(f.Name), "lang": lang, "path": fmt.Sprintf("/subtitle/%s/%d/%s", id, i, path.Base(f.Name))})
	}
	return out
}
func (s *Server) subtitle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.WriteHeader(405)
		return
	}
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/subtitle/"), "/", 3)
	if len(parts) != 3 {
		http.NotFound(w, r)
		return
	}
	index, e := strconv.Atoi(parts[1])
	if e != nil {
		http.NotFound(w, r)
		return
	}
	s.mu.Lock()
	session := s.sessions[parts[0]]
	s.mu.Unlock()
	if session == nil || index < 0 || index >= len(session.subtitles) {
		http.NotFound(w, r)
		return
	}
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	stop := context.AfterFunc(session.ctx, cancel)
	defer stop()
	f := session.subtitles[index]
	// Subtitle sizes are learned on their actual GET, never by startup probes.
	reader := f.Reader(ctx, 0)
	defer reader.Close()
	var first [1]byte
	if _, err := reader.ReadAt(first[:], 0); err != nil {
		http.Error(w, "subtitle unavailable", 502)
		return
	}
	content := (&Content{Name: f.Name, Size: f.Size(), direct: f}).Reader(ctx, 0)
	defer content.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, f.Name, time.Time{}, content)
}
