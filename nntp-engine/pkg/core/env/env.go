package env

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
)

const (
	ADDONPort                          = "ADDON_PORT"
	ADDONBaseURL                       = "ADDON_BASE_URL"
	LOGLevel                           = "LOG_LEVEL"
	LOGPath                            = "LOG_PATH"
	StreamNZBLogPathEnv                = "STREAMNZB_LOG_PATH"
	KeepLogFiles                       = "KEEP_LOG_FILES"
	AvailNZBURL                        = "AVAILNZB_URL"
	AvailNZBAPIKey                     = "AVAILNZB_API_KEY"
	TMDBAPIKey                         = "TMDB_API_KEY"
	TVDBAPIKey                         = "TVDB_API_KEY"
	MetadataEnabledEnv                 = "METADATA_ENABLED"
	NNTPProxyPort                      = "NNTP_PROXY_PORT"
	NNTPProxyHost                      = "NNTP_PROXY_HOST"
	NNTPProxyEnabled                   = "NNTP_PROXY_ENABLED"
	NNTPProxyAuthUser                  = "NNTP_PROXY_AUTH_USER"
	NNTPProxyAuthPass                  = "NNTP_PROXY_AUTH_PASS"
	NewznabEnabledEnv                  = "NEWZNAB_ENABLED"
	NewznabAPIKeyEnv                   = "NEWZNAB_API_KEY"
	TZVar                              = "TZ"
	ProviderPrefix                     = "PROVIDER_"
	IndexerPrefix                      = "INDEXER_"
	IndexerQueryHeaderEnv              = "INDEXER_QUERY_HEADER"
	IndexerGrabHeaderEnv               = "INDEXER_GRAB_HEADER"
	ProviderHeaderEnv                  = "PROVIDER_HEADER"
	StreamNZBIndexerQueryHeaderEnv     = "STREAMNZB_INDEXER_QUERY_HEADER"
	StreamNZBIndexerGrabHeaderEnv      = "STREAMNZB_INDEXER_GRAB_HEADER"
	StreamNZBProviderHeaderEnv         = "STREAMNZB_PROVIDER_HEADER"
	ConfigPath                         = "CONFIG_PATH"
	DatabaseDriverEnv                  = "DATABASE_DRIVER"
	DatabaseURLEnv                     = "DATABASE_URL"
	StreamNZBDatabaseDriverEnv         = "STREAMNZB_DATABASE_DRIVER"
	StreamNZBDatabaseURLEnv            = "STREAMNZB_DATABASE_URL"
	SpeedTestNZBURLEnv                 = "STREAMNZB_SPEEDTEST_NZB_URL"
	SpeedTestMaxBytesEnv               = "STREAMNZB_SPEEDTEST_MAX_BYTES"
	SpeedTestMaxSecondsEnv             = "STREAMNZB_SPEEDTEST_MAX_SECONDS"
	SpeedTestStepSecondsEnv            = "STREAMNZB_SPEEDTEST_STEP_SECONDS"
	EasynewsAdvancedSearchEnv          = "EASYNEWS_ADVANCED_SEARCH"
	StreamNZBEasynewsAdvancedSearchEnv = "STREAMNZB_EASYNEWS_ADVANCED_SEARCH"
	EasynewsSpamFilterEnv              = "EASYNEWS_SPAM_FILTER"
	StreamNZBEasynewsSpamFilterEnv     = "STREAMNZB_EASYNEWS_SPAM_FILTER"
	NNTPPipelineDepthEnv               = "STREAMNZB_NNTP_PIPELINE_DEPTH"
	EasynewsFileExtensionsEnv          = "EASYNEWS_FILE_EXTENSIONS"
	StreamNZBEasynewsFileExtensionsEnv = "STREAMNZB_EASYNEWS_FILE_EXTENSIONS"
)

const (
	KeyAddonPort          = "addon_port"
	KeyAddonBaseURL       = "addon_base_url"
	KeyLogLevel           = "log_level"
	KeyKeepLogFiles       = "keep_log_files"
	KeyProxyPort          = "proxy_port"
	KeyProxyHost          = "proxy_host"
	KeyProxyEnabled       = "proxy_enabled"
	KeyProxyAuthUser      = "proxy_auth_user"
	KeyProxyAuthPass      = "proxy_auth_pass"
	KeyNewznabEnabled     = "newznab_enabled"
	KeyNewznabAPIKey      = "newznab_api_key"
	KeyProviders          = "providers"
	KeyIndexers           = "indexers"
	KeyAvailNZBURL        = "availnzb_url"
	KeyAvailNZBAPIKey     = "availnzb_api_key"
	KeyTMDBAPIKey         = "tmdb_api_key"
	KeyTVDBAPIKey         = "tvdb_api_key"
	KeyMetadataEnabled    = "metadata_enabled"
	KeyIndexerQueryHeader = "indexer_query_header"
	KeyIndexerGrabHeader  = "indexer_grab_header"
	KeyProviderHeader     = "provider_header"
	KeyAdminUsername      = "admin_username"
	KeyAdminMustChangePwd = "admin_must_change_password"
	KeyDatabaseDriver     = "database_driver"
	KeyDatabaseURL        = "database_url"
)

const AdminUsernameEnv = "ADMIN_USERNAME"
const AdminForcePasswordResetEnv = "ADMIN_FORCE_PASSWORD_RESET"

var DefaultIndexerUserAgent = "StreamNZB/dev"
var runtimeHeadersMu sync.RWMutex
var runtimeIndexerQueryHeader = ""
var runtimeIndexerGrabHeader = ""
var runtimeProviderHeader = ""

func TZ() string {
	return os.Getenv(TZVar)
}

func IndexerQueryHeader() string {
	if v := os.Getenv(StreamNZBIndexerQueryHeaderEnv); v != "" {
		return v
	}
	if v := os.Getenv(IndexerQueryHeaderEnv); v != "" {
		return v
	}
	runtimeHeadersMu.RLock()
	defer runtimeHeadersMu.RUnlock()
	if runtimeIndexerQueryHeader != "" {
		return runtimeIndexerQueryHeader
	}
	return DefaultIndexerUserAgent
}

func IndexerGrabHeader() string {
	if v := os.Getenv(StreamNZBIndexerGrabHeaderEnv); v != "" {
		return v
	}
	if v := os.Getenv(IndexerGrabHeaderEnv); v != "" {
		return v
	}
	runtimeHeadersMu.RLock()
	defer runtimeHeadersMu.RUnlock()
	if runtimeIndexerGrabHeader != "" {
		return runtimeIndexerGrabHeader
	}
	return DefaultIndexerUserAgent
}

func ProviderHeader() string {
	if v := os.Getenv(StreamNZBProviderHeaderEnv); v != "" {
		return v
	}
	if v := os.Getenv(ProviderHeaderEnv); v != "" {
		return v
	}
	runtimeHeadersMu.RLock()
	defer runtimeHeadersMu.RUnlock()
	if runtimeProviderHeader != "" {
		return runtimeProviderHeader
	}
	return DefaultIndexerUserAgent
}

func SetRuntimeHeaders(indexerQueryHeader, indexerGrabHeader, providerHeader string) {
	runtimeHeadersMu.Lock()
	defer runtimeHeadersMu.Unlock()
	runtimeIndexerQueryHeader = strings.TrimSpace(indexerQueryHeader)
	runtimeIndexerGrabHeader = strings.TrimSpace(indexerGrabHeader)
	runtimeProviderHeader = strings.TrimSpace(providerHeader)
}

func LogLevel() string {
	if v := os.Getenv(LOGLevel); v != "" {
		return v
	}
	return "INFO"
}

// LogPath is where the log file goes: a file path, or a directory to write
// streamnzb.log into. Empty keeps it in the data directory. Deliberately env
// (and flag) only — it is a deployment concern, not a user setting, so it is
// not part of Config.
func LogPath() string {
	if v := os.Getenv(StreamNZBLogPathEnv); v != "" {
		return v
	}
	return os.Getenv(LOGPath)
}

// Speed test ceilings. Like LogPath these are deliberately env (and flag) only:
// they bound how much provider quota and wall clock a benchmark may spend, which
// is a deployment concern rather than a per-user setting, so they stay out of
// Config and the settings UI.
const (
	// The 10 GB reference post rather than the 1 GB one: a multi-gigabit line
	// reads a gigabyte in under two seconds, and once the corpus wraps the run
	// re-reads articles the provider has just served — likely still in its cache,
	// so the numbers stop describing a cold fetch.
	DefaultSpeedTestNZBURL = "https://sabnzbd.org/tests/test_download_10GB.nzb"
	// DefaultSpeedTestMaxBytes only ever binds on a fast line: the run is capped
	// at half a minute of stepping, which a line under ~1 Gbit cannot fill even
	// in principle. It is set where a 2 Gbit line still completes a full ramp,
	// because a ceiling that cuts the top step short reports the ceiling rather
	// than the provider.
	DefaultSpeedTestMaxBytes    = int64(4) << 30 // 4 GiB of provider quota
	DefaultSpeedTestMaxSeconds  = 60
	DefaultSpeedTestStepSeconds = 6
)

// SpeedTestNZBURL is the NZB whose articles the throughput test downloads.
// A fixed public post keeps results comparable across providers and installs.
func SpeedTestNZBURL() string {
	if v := strings.TrimSpace(os.Getenv(SpeedTestNZBURLEnv)); v != "" {
		return v
	}
	return DefaultSpeedTestNZBURL
}

// SpeedTestMaxBytes caps the wire bytes one benchmark run may download. It is
// parsed as a 64-bit value: the useful settings are several GiB, which does not
// fit an int on a 32-bit build.
func SpeedTestMaxBytes() int64 {
	if v, err := strconv.ParseInt(strings.TrimSpace(os.Getenv(SpeedTestMaxBytesEnv)), 10, 64); err == nil && v > 0 {
		return v
	}
	return DefaultSpeedTestMaxBytes
}

// SpeedTestMaxSeconds caps the wall clock of one benchmark run, ramp included.
func SpeedTestMaxSeconds() int {
	if v := positiveInt(SpeedTestMaxSecondsEnv); v > 0 {
		return v
	}
	return DefaultSpeedTestMaxSeconds
}

// SpeedTestStepSeconds is how long each connection-count step of the ramp runs,
// warm-up included.
func SpeedTestStepSeconds() int {
	if v := positiveInt(SpeedTestStepSecondsEnv); v > 0 {
		return v
	}
	return DefaultSpeedTestStepSeconds
}

// NNTPPipelineDepth is how many BODY commands a read-ahead batch keeps
// outstanding on one connection. Unset takes the pool's default; 1 turns
// pipelining off, which is the escape hatch for a provider that mishandles more
// than one command in flight. Like the speed-test ceilings this is deliberately
// env-only: a deployment-level tuning knob shaped by the round-trip time to the
// provider, not a per-user setting, so it stays out of Config and the UI.
//
// Negative values are returned as -1 so the pool can tell "explicitly off" from
// "unset"; junk parses as unset.
func NNTPPipelineDepth() int {
	v, err := strconv.Atoi(strings.TrimSpace(os.Getenv(NNTPPipelineDepthEnv)))
	if err != nil {
		return 0
	}
	if v < 0 {
		return -1
	}
	return v
}

// EasynewsAdvancedSearch reports whether searches use Easynews' advanced mode,
// which filters spam and non-video extensions server-side so fewer junk rows
// ever reach our own filters. On by default — it is what the upstream Easynews
// addons use. Like LogPath and the speed-test ceilings this is deliberately
// env-only: a deployment-level escape hatch, not a per-user setting, so it
// stays out of Config and the settings UI.
func EasynewsAdvancedSearch() bool {
	return envBool(StreamNZBEasynewsAdvancedSearchEnv, EasynewsAdvancedSearchEnv, true)
}

// EasynewsSpamFilter reports whether advanced searches ask Easynews to drop
// posts it has flagged as spam. Follows EasynewsAdvancedSearch unless set.
func EasynewsSpamFilter() bool {
	return envBool(StreamNZBEasynewsSpamFilterEnv, EasynewsSpamFilterEnv, EasynewsAdvancedSearch())
}

// EasynewsFileExtensions overrides the container whitelist sent as fex, as a
// comma-separated list without dots. Empty means the indexer sends its own
// list, which is the same one it filters results by.
func EasynewsFileExtensions() string {
	v := strings.TrimSpace(os.Getenv(StreamNZBEasynewsFileExtensionsEnv))
	if v == "" {
		v = strings.TrimSpace(os.Getenv(EasynewsFileExtensionsEnv))
	}
	return v
}

// parseBool is the one boolean vocabulary this package speaks.
//
// There used to be two. envBool accepted yes/on/no/off and treated anything
// else as unset; getEnvBool accepted only "true" and "1" and read everything
// else — including "yes" and "on" — as *false*. Since the numbered blocks went
// through the second one, `PROVIDER_1_ENABLED: yes` disabled the provider and
// `PROVIDER_1_SSL: on` silently dropped TLS. Both are ordinary YAML for true.
//
// ok reports whether the value was understood. An unrecognised value is not
// false: it means the operator has not successfully said anything, so the
// caller's default stands and MalformedBooleans reports it at startup.
func parseBool(value string) (parsed bool, ok bool) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "1", "true", "yes", "on":
		return true, true
	case "0", "false", "no", "off":
		return false, true
	}
	return false, false
}

// envBool reads primary, then legacy, falling back to def. An unrecognised
// value counts as unset so a typo cannot silently flip a default.
func envBool(primary, legacy string, def bool) bool {
	for _, name := range []string{primary, legacy} {
		if name == "" {
			continue
		}
		if v, ok := parseBool(os.Getenv(name)); ok {
			return v
		}
	}
	return def
}

// MalformedBool names an environment variable whose value this package could
// not read as a boolean.
type MalformedBool struct {
	Name  string
	Value string
}

// booleanEnvNames lists the fixed-name boolean variables. The numbered
// PROVIDER_n_* and INDEXER_n_* blocks are generated in MalformedBooleans, since
// their names depend on the index.
//
// TestBooleanEnvNamesCoversEveryFixedName keeps this in step with the calls
// themselves, so a new switch cannot be added without becoming checkable.
var booleanEnvNames = []string{
	MetadataEnabledEnv,
	NNTPProxyEnabled,
	NewznabEnabledEnv,
	AdminForcePasswordResetEnv,
	EasynewsAdvancedSearchEnv,
	StreamNZBEasynewsAdvancedSearchEnv,
	EasynewsSpamFilterEnv,
	StreamNZBEasynewsSpamFilterEnv,
}

// MalformedBooleans returns every boolean variable that is set to something
// this package does not understand.
//
// An unrecognised value leaves the default in place rather than flipping it,
// which is the safe behaviour but also a silent one — the operator wrote
// something and nothing happened. The caller logs these at startup, because
// this package cannot: logger imports env, so env cannot import logger.
func MalformedBooleans() []MalformedBool {
	var out []MalformedBool
	check := func(name string) {
		v, set := os.LookupEnv(name)
		if !set || strings.TrimSpace(v) == "" {
			return
		}
		if _, ok := parseBool(v); !ok {
			out = append(out, MalformedBool{Name: name, Value: v})
		}
	}
	for _, name := range booleanEnvNames {
		check(name)
	}
	for i := 1; i <= maxNumberedBlocks; i++ {
		for _, suffix := range []string{"ENABLED", "BACKUP", "SSL"} {
			check(fmt.Sprintf("%s%d_%s", ProviderPrefix, i, suffix))
		}
		check(fmt.Sprintf("%s%d_ENABLED", IndexerPrefix, i))
	}
	return out
}

// positiveInt reads name as a positive integer, returning 0 when unset or junk.
func positiveInt(name string) int {
	v, err := strconv.Atoi(strings.TrimSpace(os.Getenv(name)))
	if err != nil || v <= 0 {
		return 0
	}
	return v
}

type Provider struct {
	Name          string
	Host          string
	Port          int
	Username      string
	Password      string
	Connections   int
	UseSSL        bool
	Priority      *int
	Enabled       *bool
	Backup        *bool
	PipelineDepth *int
}

type Indexer struct {
	Name    string
	URL     string
	APIKey  string
	Enabled *bool
}

type ConfigOverrides struct {
	AddonPort          int
	AddonBaseURL       string
	LogLevel           string
	KeepLogFiles       int
	AvailNZBURL        string
	AvailNZBAPIKey     string
	TMDBAPIKey         string
	TVDBAPIKey         string
	IndexerQueryHeader string
	IndexerGrabHeader  string
	ProviderHeader     string
	ProxyPort          int
	ProxyHost          string
	ProxyEnabled       bool
	ProxyAuthUser      string
	ProxyAuthPass      string
	NewznabEnabled     bool
	NewznabAPIKey      string
	AdminUsername      string
	AdminMustChangePwd bool
	DatabaseDriver     string
	DatabaseURL        string
	MetadataEnabled    bool
	Providers          []Provider
	Indexers           []Indexer
}

// envReader accumulates config overrides read from the environment, tracking
// which keys were actually set so callers can tell an env-owned field from an
// unset one.
type envReader struct {
	o    *ConfigOverrides
	keys []string
}

// str assigns the first non-empty value among envVars to dst and records key.
// Multiple envVars support the STREAMNZB_-prefixed name plus its legacy alias.
func (r *envReader) str(dst *string, key string, envVars ...string) {
	for _, name := range envVars {
		if v := os.Getenv(name); v != "" {
			*dst = v
			r.keys = append(r.keys, key)
			return
		}
	}
}

// intVal assigns an int-valued env var to dst when it parses and passes valid.
func (r *envReader) intVal(dst *int, key, envVar string, valid func(int) bool) {
	v := os.Getenv(envVar)
	if v == "" {
		return
	}
	n, err := strconv.Atoi(v)
	if err != nil || (valid != nil && !valid(n)) {
		return
	}
	*dst = n
	r.keys = append(r.keys, key)
}

func ReadConfigOverrides() (ConfigOverrides, []string) {
	var o ConfigOverrides
	r := &envReader{o: &o}

	r.intVal(&o.AddonPort, KeyAddonPort, ADDONPort, nil)
	r.str(&o.AddonBaseURL, KeyAddonBaseURL, ADDONBaseURL)
	r.str(&o.LogLevel, KeyLogLevel, LOGLevel)
	r.intVal(&o.KeepLogFiles, KeyKeepLogFiles, KeepLogFiles, func(n int) bool { return n >= 1 })
	r.str(&o.AvailNZBAPIKey, KeyAvailNZBAPIKey, AvailNZBAPIKey)
	r.str(&o.TMDBAPIKey, KeyTMDBAPIKey, TMDBAPIKey)
	r.str(&o.TVDBAPIKey, KeyTVDBAPIKey, TVDBAPIKey)
	if v, ok := os.LookupEnv(MetadataEnabledEnv); ok && v != "" {
		o.MetadataEnabled = getEnvBool(MetadataEnabledEnv, false)
		r.keys = append(r.keys, KeyMetadataEnabled)
	}
	r.str(&o.IndexerQueryHeader, KeyIndexerQueryHeader, StreamNZBIndexerQueryHeaderEnv, IndexerQueryHeaderEnv)
	r.str(&o.IndexerGrabHeader, KeyIndexerGrabHeader, StreamNZBIndexerGrabHeaderEnv, IndexerGrabHeaderEnv)
	r.str(&o.ProviderHeader, KeyProviderHeader, StreamNZBProviderHeaderEnv, ProviderHeaderEnv)
	r.intVal(&o.ProxyPort, KeyProxyPort, NNTPProxyPort, nil)
	r.str(&o.ProxyHost, KeyProxyHost, NNTPProxyHost)
	if v, ok := os.LookupEnv(NNTPProxyEnabled); ok && v != "" {
		o.ProxyEnabled = getEnvBool(NNTPProxyEnabled, true)
		r.keys = append(r.keys, KeyProxyEnabled)
	}
	r.str(&o.ProxyAuthUser, KeyProxyAuthUser, NNTPProxyAuthUser)
	r.str(&o.ProxyAuthPass, KeyProxyAuthPass, NNTPProxyAuthPass)
	if v, ok := os.LookupEnv(NewznabEnabledEnv); ok && v != "" {
		o.NewznabEnabled = getEnvBool(NewznabEnabledEnv, true)
		r.keys = append(r.keys, KeyNewznabEnabled)
	}
	r.str(&o.NewznabAPIKey, KeyNewznabAPIKey, NewznabAPIKeyEnv)
	r.str(&o.AdminUsername, KeyAdminUsername, AdminUsernameEnv)
	r.str(&o.DatabaseDriver, KeyDatabaseDriver, StreamNZBDatabaseDriverEnv, DatabaseDriverEnv)
	r.str(&o.DatabaseURL, KeyDatabaseURL, StreamNZBDatabaseURLEnv, DatabaseURLEnv)
	if v, ok := os.LookupEnv(AdminForcePasswordResetEnv); ok && v != "" && getEnvBool(AdminForcePasswordResetEnv, false) {
		o.AdminMustChangePwd = true
		r.keys = append(r.keys, KeyAdminMustChangePwd)
	}
	if o.Providers = readProvidersFromEnv(); len(o.Providers) > 0 {
		r.keys = append(r.keys, KeyProviders)
	}
	if o.Indexers = readIndexersFromEnv(); len(o.Indexers) > 0 {
		r.keys = append(r.keys, KeyIndexers)
	}

	return o, r.keys
}

func OverrideKeys() []string {
	_, keys := ReadConfigOverrides()
	return keys
}

// maxNumberedBlocks is how far the PROVIDER_n_* / INDEXER_n_* scan goes. An
// eleventh block is ignored, silently — see the backlog.
const maxNumberedBlocks = 10

func readProvidersFromEnv() []Provider {
	var list []Provider
	for i := 1; i <= maxNumberedBlocks; i++ {
		prefix := fmt.Sprintf("%s%d_", ProviderPrefix, i)
		host := os.Getenv(prefix + "HOST")
		if host == "" {
			continue
		}
		priority := getEnvInt(prefix+"PRIORITY", i)
		enabled := getEnvBool(prefix+"ENABLED", true)
		backup := getEnvBool(prefix+"BACKUP", false)
		// Absent leaves the provider on the deployment default rather than
		// pinning it to a number the operator never chose.
		var pipelineDepth *int
		if v, err := strconv.Atoi(strings.TrimSpace(os.Getenv(prefix + "PIPELINE_DEPTH"))); err == nil {
			pipelineDepth = &v
		}
		list = append(list, Provider{
			Name:          getEnv(prefix+"NAME", fmt.Sprintf("Provider %d", i)),
			Host:          host,
			Port:          getEnvInt(prefix+"PORT", 563),
			Username:      os.Getenv(prefix + "USERNAME"),
			Password:      os.Getenv(prefix + "PASSWORD"),
			Connections:   getEnvInt(prefix+"CONNECTIONS", 10),
			UseSSL:        getEnvBool(prefix+"SSL", true),
			Priority:      &priority,
			Enabled:       &enabled,
			Backup:        &backup,
			PipelineDepth: pipelineDepth,
		})
	}
	return list
}

func readIndexersFromEnv() []Indexer {
	var list []Indexer
	for i := 1; i <= maxNumberedBlocks; i++ {
		prefix := fmt.Sprintf("%s%d_", IndexerPrefix, i)
		url := os.Getenv(prefix + "URL")
		if url == "" {
			continue
		}
		enabled := getEnvBool(prefix+"ENABLED", true)
		list = append(list, Indexer{
			Name:    getEnv(prefix+"NAME", fmt.Sprintf("Indexer %d", i)),
			URL:     url,
			APIKey:  os.Getenv(prefix + "API_KEY"),
			Enabled: &enabled,
		})
	}
	return list
}

func getEnv(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

func getEnvInt(key string, defaultVal int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return defaultVal
}

func getEnvBool(key string, defaultVal bool) bool {
	return envBool(key, "", defaultVal)
}
