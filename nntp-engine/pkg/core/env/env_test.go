package env

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"strconv"
	"strings"
	"testing"
)

// clear blanks the named variables for the duration of the test. Every accessor
// in this package treats an empty value as unset, so this is equivalent to
// unsetting them, and it keeps a developer's ambient shell (or a CI runner's)
// from deciding what these tests measure.
func clear(t *testing.T, names ...string) {
	t.Helper()
	for _, name := range names {
		t.Setenv(name, "")
	}
}

// Every deployment knob in this package has a STREAMNZB_-prefixed name and a
// legacy unprefixed alias, and the order between them is the whole contract: an
// operator who sets both expects the prefixed one to win. Nothing was asserting
// it, and getting it backwards would silently ignore the newer setting.
func TestPrefixedNamesOutrankTheirLegacyAliases(t *testing.T) {
	for _, tc := range []struct {
		name     string
		prefixed string
		legacy   string
		read     func() string
	}{
		{"indexer query header", StreamNZBIndexerQueryHeaderEnv, IndexerQueryHeaderEnv, IndexerQueryHeader},
		{"indexer grab header", StreamNZBIndexerGrabHeaderEnv, IndexerGrabHeaderEnv, IndexerGrabHeader},
		{"provider header", StreamNZBProviderHeaderEnv, ProviderHeaderEnv, ProviderHeader},
		{"log path", StreamNZBLogPathEnv, LOGPath, LogPath},
		{"easynews extensions", StreamNZBEasynewsFileExtensionsEnv, EasynewsFileExtensionsEnv, EasynewsFileExtensions},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv(tc.prefixed, "from-prefixed")
			t.Setenv(tc.legacy, "from-legacy")
			if got := tc.read(); got != "from-prefixed" {
				t.Fatalf("both set: got %q, want the prefixed value", got)
			}

			t.Setenv(tc.prefixed, "")
			if got := tc.read(); got != "from-legacy" {
				t.Fatalf("only legacy set: got %q, want the legacy value", got)
			}
		})
	}
}

// The header accessors fall back past both env names to a value the process
// sets at startup, and past that to the build's user agent. The chain exists so
// a config-set header still applies when no env override is present, and it is
// three levels deep, which is where they get mixed up.
func TestHeaderFallbackChain(t *testing.T) {
	clear(t, StreamNZBIndexerQueryHeaderEnv, IndexerQueryHeaderEnv)

	previous := DefaultIndexerUserAgent
	DefaultIndexerUserAgent = "StreamNZB/test"
	t.Cleanup(func() {
		DefaultIndexerUserAgent = previous
		SetRuntimeHeaders("", "", "")
	})

	SetRuntimeHeaders("", "", "")
	if got := IndexerQueryHeader(); got != "StreamNZB/test" {
		t.Fatalf("nothing set: got %q, want the build user agent", got)
	}

	SetRuntimeHeaders("from-config", "", "")
	if got := IndexerQueryHeader(); got != "from-config" {
		t.Fatalf("runtime header set: got %q", got)
	}

	t.Setenv(IndexerQueryHeaderEnv, "from-env")
	if got := IndexerQueryHeader(); got != "from-env" {
		t.Fatalf("env outranks the runtime header: got %q", got)
	}
}

// This package used to speak two boolean dialects. envBool (the Easynews
// knobs) accepted yes/on/no/off and treated anything else as unset; getEnvBool
// (the numbered blocks and the tri-state overrides) accepted only "true" and
// "1" and read everything else — "yes" and "on" included — as *false*. So
// `PROVIDER_1_ENABLED: yes` disabled the provider and `PROVIDER_1_SSL: on`
// silently dropped TLS, both of which are ordinary YAML for true.
//
// One vocabulary now, through parseBool. This asserts the accessors that used
// each dialect agree, since the whole defect was that they did not.
func TestBothAccessorsSpeakOneBooleanVocabulary(t *testing.T) {
	clearNumberedBlocks(t)
	clear(t, StreamNZBEasynewsAdvancedSearchEnv, EasynewsAdvancedSearchEnv)

	for _, in := range []string{"1", "true", "TRUE", "yes", "on", " on "} {
		t.Setenv(EasynewsAdvancedSearchEnv, in)
		if !EasynewsAdvancedSearch() {
			t.Fatalf("envBool(%q) = false, want true", in)
		}
		t.Setenv(ProviderPrefix+"1_HOST", "news.example")
		t.Setenv(ProviderPrefix+"1_SSL", in)
		if p := readProvidersFromEnv()[0]; !p.UseSSL {
			t.Fatalf("getEnvBool(%q) = false, want true — SSL must not silently drop", in)
		}
	}

	for _, in := range []string{"0", "false", "FALSE", "no", "off", " off "} {
		t.Setenv(EasynewsAdvancedSearchEnv, in)
		if EasynewsAdvancedSearch() {
			t.Fatalf("envBool(%q) = true, want false", in)
		}
		t.Setenv(ProviderPrefix+"1_SSL", in)
		if p := readProvidersFromEnv()[0]; p.UseSSL {
			t.Fatalf("getEnvBool(%q) = true, want false", in)
		}
	}
}

// An unrecognised value is not false — it means nothing was successfully said,
// so the default stands. Reading it as false is what made `SSL: yes` dangerous:
// a typo would have turned TLS off rather than leaving it on.
func TestUnrecognisedBooleansLeaveTheDefaultAlone(t *testing.T) {
	clearNumberedBlocks(t)
	clear(t, StreamNZBEasynewsAdvancedSearchEnv, EasynewsAdvancedSearchEnv)

	t.Setenv(EasynewsAdvancedSearchEnv, "banana")
	if !EasynewsAdvancedSearch() {
		t.Fatal("junk must leave the true default alone")
	}

	t.Setenv(ProviderPrefix+"1_HOST", "news.example")
	t.Setenv(ProviderPrefix+"1_SSL", "banana")
	t.Setenv(ProviderPrefix+"1_BACKUP", "banana")
	p := readProvidersFromEnv()[0]
	if !p.UseSSL {
		t.Fatal("junk must not turn SSL off")
	}
	if p.Backup == nil || *p.Backup {
		t.Fatal("junk must not turn a provider into a backup")
	}
}

// The default staying put is safe but silent, so a value nothing understood has
// to be reported. This package cannot log it — logger imports env — so it hands
// the list to the caller, which main logs at startup.
func TestMalformedBooleansReportsWhatWasIgnored(t *testing.T) {
	clearNumberedBlocks(t)
	for _, name := range booleanEnvNames {
		t.Setenv(name, "")
	}

	if got := MalformedBooleans(); len(got) != 0 {
		t.Fatalf("nothing malformed, got %+v", got)
	}

	// A well-formed value is not a complaint.
	t.Setenv(NNTPProxyEnabled, "off")
	if got := MalformedBooleans(); len(got) != 0 {
		t.Fatalf("\"off\" is valid, got %+v", got)
	}

	t.Setenv(NewznabEnabledEnv, "sure")
	t.Setenv(ProviderPrefix+"3_SSL", "maybe")
	t.Setenv(IndexerPrefix+"2_ENABLED", "affirmative")

	got := MalformedBooleans()
	found := map[string]string{}
	for _, m := range got {
		found[m.Name] = m.Value
	}
	for name, want := range map[string]string{
		NewznabEnabledEnv:           "sure",
		ProviderPrefix + "3_SSL":    "maybe",
		IndexerPrefix + "2_ENABLED": "affirmative",
	} {
		if found[name] != want {
			t.Fatalf("%s: got %q, want %q (all: %+v)", name, found[name], want, got)
		}
	}
	if len(got) != 3 {
		t.Fatalf("got %d complaints, want 3: %+v", len(got), got)
	}
}

// EasynewsSpamFilter has no default of its own: it follows the advanced-search
// switch unless set. A separate default would let spam filtering stay on with
// advanced search off, which is a mode the indexer cannot express.
func TestEasynewsSpamFilterFollowsAdvancedSearch(t *testing.T) {
	clear(t, StreamNZBEasynewsSpamFilterEnv, EasynewsSpamFilterEnv, EasynewsAdvancedSearchEnv)

	t.Setenv(StreamNZBEasynewsAdvancedSearchEnv, "false")
	if EasynewsSpamFilter() {
		t.Fatal("spam filter should follow advanced search off")
	}

	t.Setenv(StreamNZBEasynewsSpamFilterEnv, "true")
	if !EasynewsSpamFilter() {
		t.Fatal("an explicit setting must outrank what it follows")
	}
}

// Zero and -1 mean different things to the pool: unset takes its default,
// explicitly-negative turns pipelining off. Junk has to read as unset rather
// than as off, or a typo silently halves throughput on every provider.
func TestNNTPPipelineDepthSeparatesUnsetFromOff(t *testing.T) {
	clear(t, NNTPPipelineDepthEnv)
	if got := NNTPPipelineDepth(); got != 0 {
		t.Fatalf("unset: got %d, want 0", got)
	}

	for in, want := range map[string]int{
		"4":     4,
		" 4 ":   4,
		"1":     1,
		"-1":    -1,
		"-7":    -1,
		"deep":  0,
		"4.5":   0,
		"":      0,
		"99999": 99999,
	} {
		t.Setenv(NNTPPipelineDepthEnv, in)
		if got := NNTPPipelineDepth(); got != want {
			t.Fatalf("NNTPPipelineDepth(%q) = %d, want %d", in, got, want)
		}
	}
}

// The speed-test ceilings bound how much provider quota and wall clock a
// benchmark may spend, so a value that fails to parse must fall back to the
// documented default rather than to zero — a zero ceiling would either abort
// every run or, read as "no limit", spend without bound.
func TestSpeedTestCeilingsFallBackOnJunkAndNonPositiveValues(t *testing.T) {
	clear(t, SpeedTestMaxBytesEnv, SpeedTestMaxSecondsEnv, SpeedTestStepSecondsEnv, SpeedTestNZBURLEnv)

	if got := SpeedTestMaxBytes(); got != DefaultSpeedTestMaxBytes {
		t.Fatalf("unset max bytes = %d", got)
	}
	if got := SpeedTestNZBURL(); got != DefaultSpeedTestNZBURL {
		t.Fatalf("unset NZB url = %q", got)
	}

	for _, bad := range []string{"lots", "0", "-1", "  "} {
		t.Setenv(SpeedTestMaxBytesEnv, bad)
		t.Setenv(SpeedTestMaxSecondsEnv, bad)
		t.Setenv(SpeedTestStepSecondsEnv, bad)
		if got := SpeedTestMaxBytes(); got != DefaultSpeedTestMaxBytes {
			t.Fatalf("max bytes %q = %d, want the default", bad, got)
		}
		if got := SpeedTestMaxSeconds(); got != DefaultSpeedTestMaxSeconds {
			t.Fatalf("max seconds %q = %d, want the default", bad, got)
		}
		if got := SpeedTestStepSeconds(); got != DefaultSpeedTestStepSeconds {
			t.Fatalf("step seconds %q = %d, want the default", bad, got)
		}
	}

	// Several GiB does not fit an int on a 32-bit build, which is why this one
	// parses as int64. A regression to Atoi would fail here on those builds.
	t.Setenv(SpeedTestMaxBytesEnv, "8589934592")
	if got := SpeedTestMaxBytes(); got != 8589934592 {
		t.Fatalf("max bytes = %d, want 8589934592", got)
	}
}

func clearNumberedBlocks(t *testing.T) {
	t.Helper()
	for i := 1; i <= 11; i++ {
		for _, suffix := range []string{"HOST", "NAME", "PORT", "USERNAME", "PASSWORD", "CONNECTIONS", "SSL", "PRIORITY", "ENABLED", "BACKUP", "PIPELINE_DEPTH"} {
			t.Setenv(ProviderPrefix+strconv.Itoa(i)+"_"+suffix, "")
		}
		for _, suffix := range []string{"URL", "NAME", "API_KEY", "ENABLED"} {
			t.Setenv(IndexerPrefix+strconv.Itoa(i)+"_"+suffix, "")
		}
	}
}

// The numbered blocks are how a container is configured without a config file,
// so which indices are read is the contract. Two properties that are easy to
// assume wrongly: a gap does NOT end the scan (1 and 3 both load, with 2
// missing), and the scan stops at 10 — an eleventh block is silently ignored.
func TestNumberedProviderBlocksSkipGapsAndStopAtTen(t *testing.T) {
	clearNumberedBlocks(t)

	t.Setenv(ProviderPrefix+"1_HOST", "first.example")
	t.Setenv(ProviderPrefix+"3_HOST", "third.example")
	t.Setenv(ProviderPrefix+"11_HOST", "eleventh.example")

	got := readProvidersFromEnv()
	if len(got) != 2 {
		t.Fatalf("got %d providers, want 2: %+v", len(got), got)
	}
	if got[0].Host != "first.example" || got[1].Host != "third.example" {
		t.Fatalf("hosts = %q, %q", got[0].Host, got[1].Host)
	}
	for _, p := range got {
		if strings.Contains(p.Host, "eleventh") {
			t.Fatal("the eleventh block was read; the scan is bounded at ten")
		}
	}

	// The default name and priority are derived from the index, not from the
	// position in the result, so the third block stays "Provider 3".
	if got[1].Name != "Provider 3" {
		t.Fatalf("default name = %q, want %q", got[1].Name, "Provider 3")
	}
	if got[1].Priority == nil || *got[1].Priority != 3 {
		t.Fatalf("default priority = %v, want 3", got[1].Priority)
	}
}

func TestNumberedIndexerBlocksSkipGapsAndStopAtTen(t *testing.T) {
	clearNumberedBlocks(t)

	t.Setenv(IndexerPrefix+"2_URL", "https://two.example")
	t.Setenv(IndexerPrefix+"11_URL", "https://eleven.example")

	got := readIndexersFromEnv()
	if len(got) != 1 {
		t.Fatalf("got %d indexers, want 1: %+v", len(got), got)
	}
	if got[0].URL != "https://two.example" || got[0].Name != "Indexer 2" {
		t.Fatalf("indexer = %+v", got[0])
	}
	if got[0].Enabled == nil || !*got[0].Enabled {
		t.Fatal("an indexer with no ENABLED must default to on")
	}
}

// PIPELINE_DEPTH is the one provider field with no default: absent leaves the
// provider on whatever the deployment already uses, which a zero value would
// not express. Every other field falls back to a documented constant.
func TestProviderBlockDefaults(t *testing.T) {
	clearNumberedBlocks(t)
	t.Setenv(ProviderPrefix+"1_HOST", "news.example")

	p := readProvidersFromEnv()[0]
	if p.Port != 563 || p.Connections != 10 || !p.UseSSL {
		t.Fatalf("defaults = port %d, conns %d, ssl %v", p.Port, p.Connections, p.UseSSL)
	}
	if p.PipelineDepth != nil {
		t.Fatalf("PipelineDepth = %v, want nil when unset", *p.PipelineDepth)
	}
	if p.Enabled == nil || !*p.Enabled || p.Backup == nil || *p.Backup {
		t.Fatalf("enabled/backup = %v/%v", p.Enabled, p.Backup)
	}

	t.Setenv(ProviderPrefix+"1_PIPELINE_DEPTH", "1")
	if d := readProvidersFromEnv()[0].PipelineDepth; d == nil || *d != 1 {
		t.Fatalf("PipelineDepth = %v, want 1", d)
	}
}

// The returned key list is what the settings UI greys out as env-managed, so a
// key must appear only when the variable is actually set. A field that reports
// itself overridden when it is not locks the operator out of their own setting.
func TestOverrideKeysAreReportedOnlyForVariablesThatAreSet(t *testing.T) {
	clearNumberedBlocks(t)
	clear(t, ADDONPort, ADDONBaseURL, LOGLevel, KeepLogFiles, AdminUsernameEnv,
		MetadataEnabledEnv, NNTPProxyEnabled, NewznabEnabledEnv, AdminForcePasswordResetEnv,
		AvailNZBAPIKey, TMDBAPIKey, TVDBAPIKey, NNTPProxyPort, NNTPProxyHost,
		NNTPProxyAuthUser, NNTPProxyAuthPass, NewznabAPIKeyEnv,
		StreamNZBDatabaseDriverEnv, DatabaseDriverEnv, StreamNZBDatabaseURLEnv, DatabaseURLEnv,
		StreamNZBIndexerQueryHeaderEnv, IndexerQueryHeaderEnv,
		StreamNZBIndexerGrabHeaderEnv, IndexerGrabHeaderEnv,
		StreamNZBProviderHeaderEnv, ProviderHeaderEnv)

	if _, keys := ReadConfigOverrides(); len(keys) != 0 {
		t.Fatalf("nothing set, but these keys were reported overridden: %v", keys)
	}

	t.Setenv(ADDONPort, "7001")
	t.Setenv(AdminUsernameEnv, "operator")
	o, keys := ReadConfigOverrides()
	if o.AddonPort != 7001 || o.AdminUsername != "operator" {
		t.Fatalf("overrides = %+v", o)
	}
	if !contains(keys, KeyAddonPort) || !contains(keys, KeyAdminUsername) {
		t.Fatalf("keys = %v", keys)
	}
	if contains(keys, KeyAddonBaseURL) {
		t.Fatalf("an unset key was reported overridden: %v", keys)
	}
}

// A value that fails to parse or fails its range check leaves the field alone
// *and* leaves it unreported, so the UI stays editable rather than greying out
// a setting the environment did not manage to supply.
func TestUnparsableIntegersOverrideNothing(t *testing.T) {
	clearNumberedBlocks(t)
	clear(t, ADDONPort, KeepLogFiles)

	for _, bad := range []string{"seven", "", "  "} {
		t.Setenv(ADDONPort, bad)
		o, keys := ReadConfigOverrides()
		if o.AddonPort != 0 || contains(keys, KeyAddonPort) {
			t.Fatalf("ADDON_PORT=%q gave port %d, keys %v", bad, o.AddonPort, keys)
		}
	}

	// KeepLogFiles carries a range check on top of the parse: below one would
	// mean keeping no logs at all, which is not something the field can express.
	t.Setenv(ADDONPort, "")
	t.Setenv(KeepLogFiles, "0")
	o, keys := ReadConfigOverrides()
	if o.KeepLogFiles != 0 || contains(keys, KeyKeepLogFiles) {
		t.Fatalf("KEEP_LOG_FILES=0 gave %d, keys %v", o.KeepLogFiles, keys)
	}
}

// The tri-state switches are read through LookupEnv precisely so that "set to
// false" is distinguishable from "not set". Losing that would turn every
// unset switch into an override the UI refuses to edit.
func TestTriStateSwitchesDistinguishFalseFromUnset(t *testing.T) {
	clearNumberedBlocks(t)
	clear(t, MetadataEnabledEnv, NNTPProxyEnabled, NewznabEnabledEnv, ADDONPort, AdminUsernameEnv)

	if _, keys := ReadConfigOverrides(); contains(keys, KeyMetadataEnabled) || contains(keys, KeyProxyEnabled) {
		t.Fatal("unset switches were reported as overrides")
	}

	t.Setenv(MetadataEnabledEnv, "false")
	t.Setenv(NNTPProxyEnabled, "false")
	o, keys := ReadConfigOverrides()
	if o.MetadataEnabled || o.ProxyEnabled {
		t.Fatalf("explicit false was not applied: %+v", o)
	}
	if !contains(keys, KeyMetadataEnabled) || !contains(keys, KeyProxyEnabled) {
		t.Fatalf("explicit false must still count as an override: %v", keys)
	}
}

// The forced password reset is one-directional on purpose: it exists to make a
// container start with the change-password screen, and "false" must not be a
// way to clear a reset the admin has not completed.
func TestForcePasswordResetOnlyEverSetsTheFlag(t *testing.T) {
	clearNumberedBlocks(t)
	clear(t, AdminForcePasswordResetEnv, ADDONPort, AdminUsernameEnv)

	t.Setenv(AdminForcePasswordResetEnv, "false")
	o, keys := ReadConfigOverrides()
	if o.AdminMustChangePwd || contains(keys, KeyAdminMustChangePwd) {
		t.Fatalf("false must not register an override: %+v %v", o, keys)
	}

	t.Setenv(AdminForcePasswordResetEnv, "true")
	o, keys = ReadConfigOverrides()
	if !o.AdminMustChangePwd || !contains(keys, KeyAdminMustChangePwd) {
		t.Fatalf("true must set and report the flag: %+v %v", o, keys)
	}
}

func TestLogLevelDefaultsToInfo(t *testing.T) {
	clear(t, LOGLevel)
	if got := LogLevel(); got != "INFO" {
		t.Fatalf("LogLevel() = %q, want INFO", got)
	}
	t.Setenv(LOGLevel, "TRACE")
	if got := LogLevel(); got != "TRACE" {
		t.Fatalf("LogLevel() = %q", got)
	}
}

func TestTZReadsTheStandardVariable(t *testing.T) {
	t.Setenv(TZVar, "Europe/Helsinki")
	if got := TZ(); got != "Europe/Helsinki" {
		t.Fatalf("TZ() = %q", got)
	}
	if TZVar != "TZ" {
		t.Fatalf("TZVar = %q; the timezone variable is named by the runtime, not by us", TZVar)
	}
}

func contains(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}

// booleanEnvNames is only worth having if it matches the calls. This walks the
// package for every envBool/getEnvBool call whose variable name is a
// package-level constant and requires it to be listed, so a new boolean switch
// cannot be added without MalformedBooleans learning to check it.
//
// Calls whose name is built at runtime — the numbered PROVIDER_n_* and
// INDEXER_n_* blocks, which concatenate a prefix — are skipped here and covered
// by TestMalformedBooleansReportsWhatWasIgnored instead.
func TestBooleanEnvNamesCoversEveryFixedName(t *testing.T) {
	fset := token.NewFileSet()
	pkgs, err := parser.ParseDir(fset, ".", func(fi fs.FileInfo) bool {
		return !strings.HasSuffix(fi.Name(), "_test.go")
	}, 0)
	if err != nil {
		t.Fatalf("ParseDir: %v", err)
	}

	consts := map[string]bool{}
	var files []*ast.File
	for _, pkg := range pkgs {
		for _, file := range pkg.Files {
			files = append(files, file)
			for _, decl := range file.Decls {
				gd, ok := decl.(*ast.GenDecl)
				if !ok || gd.Tok != token.CONST {
					continue
				}
				for _, spec := range gd.Specs {
					if vs, ok := spec.(*ast.ValueSpec); ok {
						for _, name := range vs.Names {
							consts[name.Name] = true
						}
					}
				}
			}
		}
	}

	listed := map[string]bool{}
	for _, name := range booleanEnvNames {
		listed[name] = true
	}
	// booleanEnvNames holds values; the walk finds the constant identifiers
	// that carry them, so map one to the other by evaluating the constants.
	nameOf := map[string]string{
		"MetadataEnabledEnv": MetadataEnabledEnv, "NNTPProxyEnabled": NNTPProxyEnabled,
		"NewznabEnabledEnv": NewznabEnabledEnv, "AdminForcePasswordResetEnv": AdminForcePasswordResetEnv,
		"EasynewsAdvancedSearchEnv":          EasynewsAdvancedSearchEnv,
		"StreamNZBEasynewsAdvancedSearchEnv": StreamNZBEasynewsAdvancedSearchEnv,
		"EasynewsSpamFilterEnv":              EasynewsSpamFilterEnv,
		"StreamNZBEasynewsSpamFilterEnv":     StreamNZBEasynewsSpamFilterEnv,
	}

	for _, file := range files {
		ast.Inspect(file, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			fn, ok := call.Fun.(*ast.Ident)
			if !ok || (fn.Name != "envBool" && fn.Name != "getEnvBool") {
				return true
			}
			// envBool(primary, legacy, def); getEnvBool(key, def).
			for i, arg := range call.Args {
				if i > 1 {
					break
				}
				id, ok := arg.(*ast.Ident)
				if !ok || !consts[id.Name] {
					continue
				}
				value, known := nameOf[id.Name]
				if !known {
					t.Errorf("%s: %s(%s) uses a constant this test does not know; add it to nameOf and to booleanEnvNames",
						fset.Position(arg.Pos()), fn.Name, id.Name)
					continue
				}
				if !listed[value] {
					t.Errorf("%s: %s(%s) reads a boolean that booleanEnvNames does not list, so MalformedBooleans will never report it",
						fset.Position(arg.Pos()), fn.Name, id.Name)
				}
			}
			return true
		})
	}
}
