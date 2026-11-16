package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The optional phone editor uses the TV's native evaluator for previews, not a second JS sorter. */
internal object UsenetRankingWebPage {
    val style = """
      .ranking-note { color: rgba(255,255,255,.6); font-size: .85rem; margin: .5rem 0 1rem; }
      .ranking-section { border: 1px solid #282828; border-radius: 16px; margin: 1rem 0; padding: 1rem; }
      .ranking-section > summary { cursor: pointer; font-weight: 650; padding: .2rem 0; }
      .ranking-section[open] > summary { margin-bottom: 1rem; }
      .ranking-savebar { position: sticky; top: .5rem; z-index: 5; background: rgba(0,0,0,.95); padding: .4rem 0; }
      .ranking-rule > summary { cursor: pointer; font-weight: 550; overflow-wrap: anywhere; }
      .ranking-rule[open] > summary { margin-bottom: 1rem; }
      .ranking-card { background: #111; border: 1px solid #292929; border-radius: 12px; padding: .85rem; margin: .7rem 0; }
      .ranking-controls { display: flex; flex-wrap: wrap; gap: .4rem; margin: .4rem 0 .8rem; }
      .ranking-controls button { border: 1px solid #444; background: transparent; color: #ddd; border-radius: 8px; padding: .45rem .7rem; font: inherit; font-size: .8rem; }
      .ranking-condition { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; margin-top: .75rem; border-top: 1px solid #333; padding-top: .75rem; }
      .ranking-condition .wide { grid-column: 1 / -1; }
      textarea { width: 100%; min-height: 180px; padding: .8rem; border: 1px solid #444; border-radius: 12px; background: #090909; color: #eee; font: .85rem/1.6 ui-monospace, monospace; resize: vertical; }
      .ranking-result { padding: .8rem 0; border-bottom: 1px solid #292929; overflow-wrap: anywhere; }
      .ranking-result b { font-size: .88rem; font-weight: 550; }
      .ranking-result small { display: block; color: #aaa; margin-top: .3rem; }
      #rankingDirty { min-height: 1.4rem; font-size: .8rem; color: #e4c06b; margin-bottom: .5rem; }
      #rankingImportFile { margin-top: .8rem; }
      @media (max-width: 420px) { .ranking-controls button { flex-grow: 1; } .ranking-condition { grid-template-columns: 1fr; } }
    """.trimIndent()

    fun panel(context: Context): String {
        fun s(id: Int) = context.getString(id).escape()
        return """
        <div id="panel-ranking" hidden>
          <p class="ranking-note">${s(R.string.usenet_ranking_intro)}</p>
          <label for="rankingPreset">${s(R.string.usenet_ranking_title)}</label>
          <select id="rankingPreset"></select>
          <p class="ranking-note" id="rankingDescription"></p>
          <div id="rankingQuickPreferences">
            <label for="rankingPreferredLanguage">${s(R.string.usenet_ranking_language)}</label>
            <select id="rankingPreferredLanguage"></select>
            <p class="ranking-note">${s(R.string.usenet_ranking_language_hint)}</p>
          </div>
          <div class="ranking-savebar">
            <div id="rankingDirty" role="status" aria-live="polite"></div>
            <button class="btn primary add" type="button" id="saveRanking">${s(R.string.action_save)}</button>
          </div>
          <details class="ranking-section" id="rankingCustomize">
            <summary>${s(R.string.usenet_ranking_customize)}</summary>
            <label for="rankingName">${s(R.string.usenet_ranking_name)}</label><input id="rankingName" data-ranking-path="name" maxlength="80">
            <label for="rankingLanguages">${s(R.string.usenet_ranking_languages)}</label>
            <input id="rankingLanguages" data-ranking-path="preferredLanguages" data-kind="languages" autocapitalize="none" spellcheck="false" placeholder="en, ja">
            <p class="ranking-note">${s(R.string.usenet_ranking_languages_hint)}</p>
            <div class="row">
              <div><label for="rankingMovieSize">${s(R.string.usenet_ranking_movie_size)}</label><input id="rankingMovieSize" data-ranking-path="preferredMovieSizeGb" data-kind="number" type="number" min="0" max="1000" step="0.1" inputmode="decimal"></div>
              <div><label for="rankingEpisodeSize">${s(R.string.usenet_ranking_episode_size)}</label><input id="rankingEpisodeSize" data-ranking-path="preferredEpisodeSizeGb" data-kind="number" type="number" min="0" max="1000" step="0.1" inputmode="decimal"></div>
            </div>
            <p class="ranking-note">${s(R.string.usenet_ranking_size_hint)}</p>
            <label for="rankingBucket">${s(R.string.usenet_ranking_bucket)}</label><input id="rankingBucket" data-ranking-path="topPerBucket" data-kind="number" type="number" min="0" max="20" step="1" inputmode="numeric">
            <p class="ranking-note">${s(R.string.usenet_ranking_bucket_hint)}</p>
            <details class="ranking-section"><summary>${s(R.string.usenet_ranking_priorities)}</summary>
              <p class="ranking-note">${s(R.string.usenet_ranking_priorities_hint)}</p><div id="rankingSort"></div>
              <button class="btn add" type="button" data-ranking-add="sort">${s(R.string.usenet_ranking_add_priority)}</button>
            </details>
            <details class="ranking-section"><summary>${s(R.string.usenet_ranking_tiers)}</summary>
              <p class="ranking-note">${s(R.string.usenet_ranking_tiers_hint)}</p><div id="rankingTiers"></div>
              <button class="btn add" type="button" data-ranking-add="tiers">${s(R.string.usenet_ranking_add_tier)}</button>
            </details>
            <details class="ranking-section"><summary>${s(R.string.usenet_ranking_rules)}</summary>
              <p class="ranking-note">${s(R.string.usenet_ranking_rules_hint)}</p><div id="rankingRules"></div>
              <button class="btn add" type="button" data-ranking-add="rules">${s(R.string.usenet_ranking_add_rule)}</button>
              <p class="ranking-note">${s(R.string.usenet_ranking_values_hint)}</p>
            </details>
          </details>
          <details class="ranking-section"><summary>${s(R.string.usenet_ranking_preview)}</summary>
            <p class="ranking-note">${s(R.string.usenet_ranking_preview_hint)}</p>
            <label for="rankingMedia">${s(R.string.usenet_ranking_field_media)}</label>
            <select id="rankingMedia"><option value="movie">${s(R.string.usenet_ranking_movies)}</option><option value="series">${s(R.string.usenet_ranking_episodes)}</option></select>
            <textarea id="rankingSamples" aria-label="${s(R.string.usenet_ranking_preview)}" spellcheck="false">Movie.2025.2160p.UHD.BluRay.REMUX.HDR.TrueHD.Atmos-FraMeSToR | 65 | 120
Movie.2025.1080p.BluRay.REMUX-CiNEPHiLES | 18 | 90
Movie.2025.2160p.WEB-DL.DDP.HDR-FLUX | 16 | 20
Movie.2025.2160p.WEB-DL-Unknown | 8 | 100
Movie.2025.1080p.WEB-DL.HEVC-NTb | 4 | 5
Movie.2025.720p.WEBRip-Other | 1 | 2</textarea>
            <button class="btn add" type="button" id="previewRanking">${s(R.string.usenet_ranking_preview)}</button>
            <div id="rankingResults" aria-live="polite"></div>
          </details>
          <details class="ranking-section"><summary>${s(R.string.usenet_ranking_import_export)}</summary>
            <p class="ranking-note">${s(R.string.usenet_ranking_import_hint)}</p>
            <button class="btn" type="button" id="exportRanking">${s(R.string.usenet_ranking_export)}</button>
            <label for="rankingImportFile" style="margin-top:1rem">${s(R.string.usenet_ranking_import)}</label>
            <input id="rankingImportFile" type="file" accept=".json,application/json">
          </details>
        </div>
        """.trimIndent()
    }

    fun script(context: Context): String {
        fun j(id: Int) = Json.encodeToString(context.getString(id)).replace("<", "\\u003c")
        return """
const rankingText = {
 priority: ${j(R.string.usenet_ranking_priorities)}, direction: ${j(R.string.usenet_ranking_order)},
 action: ${j(R.string.usenet_ranking_action)}, conditions: ${j(R.string.usenet_ranking_all)},
 field: ${j(R.string.usenet_ranking_condition_field)}, operator: ${j(R.string.usenet_ranking_condition_operator)},
 custom: ${j(R.string.usenet_ranking_custom)}, draft: ${j(R.string.usenet_ranking_draft)},
 customDesc: ${j(R.string.usenet_ranking_custom_desc)}, name: ${j(R.string.usenet_ranking_name)},
 up: ${j(R.string.usenet_ranking_up)}, down: ${j(R.string.usenet_ranking_down)}, remove: ${j(R.string.usenet_ranking_remove)},
 tierName: ${j(R.string.usenet_ranking_tier_name)}, resolutions: ${j(R.string.usenet_ranking_resolutions)}, qualities: ${j(R.string.usenet_ranking_qualities)},
 ruleName: ${j(R.string.usenet_ranking_rule_name)}, enabled: ${j(R.string.usenet_ranking_enabled)},
 all: ${j(R.string.usenet_ranking_all)}, any: ${j(R.string.usenet_ranking_any)},
 boost: ${j(R.string.usenet_ranking_boost)}, exclude: ${j(R.string.usenet_ranking_exclude)}, require: ${j(R.string.usenet_ranking_require)},
 score: ${j(R.string.usenet_ranking_score)}, minimum: ${j(R.string.usenet_ranking_minimum)},
 minimumHint: ${j(R.string.usenet_ranking_minimum_hint)}, value: ${j(R.string.usenet_ranking_value)},
 addCondition: ${j(R.string.usenet_ranking_add_condition)}, highest: ${j(R.string.usenet_ranking_descending)}, lowest: ${j(R.string.usenet_ranking_ascending)},
 pending: ${j(R.string.usenet_ranking_previewing)}, shown: ${j(R.string.usenet_ranking_preview_count)},
 excluded: ${j(R.string.usenet_ranking_excluded)}, more: ${j(R.string.usenet_ranking_more)}, unmatched: ${j(R.string.usenet_ranking_unmatched)},
 invalid: ${j(R.string.usenet_ranking_invalid)}, imported: ${j(R.string.usenet_ranking_imported)}
};
const rankingKeys = {
 TIER: ${j(R.string.usenet_ranking_key_tier)}, SCORE: ${j(R.string.usenet_ranking_key_score)}, LANGUAGE: ${j(R.string.usenet_ranking_key_language)},
 RESOLUTION: ${j(R.string.usenet_ranking_key_resolution)}, QUALITY: ${j(R.string.usenet_ranking_key_quality)},
 SIZE: ${j(R.string.usenet_ranking_key_size)}, AGE: ${j(R.string.usenet_ranking_key_age)}, INDEXER: ${j(R.string.usenet_ranking_key_indexer)},
 SIZE_PREFERENCE: ${j(R.string.usenet_ranking_key_size_preference)}
};
const rankingFields = {
 TITLE: ${j(R.string.usenet_ranking_field_title)}, GROUP: ${j(R.string.usenet_ranking_field_group)},
 QUALITY: ${j(R.string.usenet_ranking_key_quality)}, RESOLUTION: ${j(R.string.usenet_ranking_key_resolution)},
 CODEC: ${j(R.string.usenet_ranking_field_codec)}, VISUAL: ${j(R.string.usenet_ranking_field_visual)}, AUDIO: ${j(R.string.usenet_ranking_field_audio)},
 LANGUAGE: ${j(R.string.usenet_ranking_field_language)}, INDEXER: ${j(R.string.usenet_ranking_field_indexer)},
 SIZE_GB: ${j(R.string.usenet_ranking_key_size)}, AGE_DAYS: ${j(R.string.usenet_ranking_field_age)}, MEDIA_TYPE: ${j(R.string.usenet_ranking_field_media)}
};
const rankingOperators = { IS: ${j(R.string.usenet_ranking_is)}, IS_NOT: ${j(R.string.usenet_ranking_is_not)},
 CONTAINS: ${j(R.string.usenet_ranking_contains)}, MATCHES: ${j(R.string.usenet_ranking_matches)},
 AT_LEAST: ${j(R.string.usenet_ranking_at_least)}, AT_MOST: ${j(R.string.usenet_ranking_at_most)} };
let rankingState = null, rankingDraft = null, rankingCustomDraft = null, rankingExpandRule = null;
const rankingClone = value => JSON.parse(JSON.stringify(value));
const rankingCsv = value => String(value || '').split(',').map(s => s.trim()).filter(Boolean);
function rankingOptions(values, selected) {
 return Object.entries(values).map(([key, label]) => '<option value="' + key + '"' + (String(selected) === key ? ' selected' : '') + '>' + escapeHtml(label) + '</option>').join('');
}
function rankingSelect(path, values, value, kind) {
 const label = path.endsWith('.key') ? rankingText.priority : path.endsWith('.descending') ? rankingText.direction :
  path.endsWith('.action') ? rankingText.action : path.endsWith('.matchAny') ? rankingText.conditions :
  path.endsWith('.field') ? rankingText.field : rankingText.operator;
 return '<select aria-label="' + escapeHtml(label) + '" data-ranking-path="' + path + '"' + (kind ? ' data-kind="' + kind + '"' : '') + '>' + rankingOptions(values, value) + '</select>';
}
function rankingInput(path, label, value, kind, type) {
 const id = 'rank-' + path.replaceAll('.', '-');
 return '<label for="' + id + '">' + escapeHtml(label) + '</label><input id="' + id + '" data-ranking-path="' + path + '"' +
  (kind ? ' data-kind="' + kind + '"' : '') + ' type="' + (type || 'text') + '" value="' + escapeHtml(value == null ? '' : value) + '"' +
  (type === 'number' ? ' step="any" inputmode="decimal"' : ' spellcheck="false"') + '>';
}
function rankingControls(section, index, length) {
 return '<div class="ranking-controls">' +
  '<button type="button" data-ranking-move="' + section + '" data-index="' + index + '" data-offset="-1"' + (index === 0 ? ' disabled' : '') + '>' + escapeHtml(rankingText.up) + '</button>' +
  '<button type="button" data-ranking-move="' + section + '" data-index="' + index + '" data-offset="1"' + (index === length - 1 ? ' disabled' : '') + '>' + escapeHtml(rankingText.down) + '</button>' +
  '<button type="button" data-ranking-remove="' + section + '" data-index="' + index + '">' + escapeHtml(rankingText.remove) + '</button></div>';
}
function rankingRuleSummary(rule) {
 const action = rule.action === 'BOOST' ? rankingText.score + ': ' + rule.score : rule.action === 'REQUIRE' ? rankingText.require : rankingText.exclude;
 return rule.name + ' · ' + action + (rule.enabled ? '' : ' · ' + state(false));
}
function renderRankingLists() {
 const openRules = new Set(Array.from($('rankingRules').querySelectorAll('details[open]')).map(d => Number(d.dataset.ruleIndex)));
 if (rankingExpandRule !== null) { openRules.add(rankingExpandRule); rankingExpandRule = null; }
 $('rankingSort').innerHTML = rankingDraft.sort.map((sort, i, all) => '<div class="ranking-card">' +
  rankingSelect('sort.' + i + '.key', rankingKeys, sort.key) + rankingSelect('sort.' + i + '.descending', {true: rankingText.highest, false: rankingText.lowest}, sort.descending, 'bool-select') +
  rankingControls('sort', i, all.length) + '</div>').join('');
 $('rankingTiers').innerHTML = rankingDraft.tiers.map((tier, i, all) => '<div class="ranking-card">' +
  rankingInput('tiers.' + i + '.name', rankingText.tierName, tier.name) +
  rankingInput('tiers.' + i + '.resolutions', rankingText.resolutions, tier.resolutions.join(', '), 'resolutions') +
  rankingInput('tiers.' + i + '.qualities', rankingText.qualities, tier.qualities.join(', '), 'qualities') +
  rankingControls('tiers', i, all.length) + '</div>').join('');
 $('rankingRules').innerHTML = rankingDraft.rules.map((rule, i, all) => {
  const p = 'rules.' + i + '.';
  const conditions = rule.conditions.map((condition, c) => {
   const path = p + 'conditions.' + c + '.';
   const numeric = ['RESOLUTION', 'SIZE_GB', 'AGE_DAYS'].includes(condition.field);
   const operators = Object.fromEntries(Object.entries(rankingOperators).filter(([key]) =>
    numeric ? ['IS', 'IS_NOT', 'AT_LEAST', 'AT_MOST'].includes(key) : !['AT_LEAST', 'AT_MOST'].includes(key)));
   return '<div class="ranking-condition">' + rankingSelect(path + 'field', rankingFields, condition.field) +
    rankingSelect(path + 'operator', operators, condition.operator) + '<div class="wide">' + rankingInput(path + 'value', rankingText.value, condition.value) +
    '<button class="btn" type="button" data-ranking-condition-remove="' + i + '" data-index="' + c + '">' + escapeHtml(rankingText.remove) + '</button></div></div>';
  }).join('');
  return '<details class="ranking-card ranking-rule" data-rule-index="' + i + '"' + (openRules.has(i) ? ' open' : '') + '><summary>' + escapeHtml(rankingRuleSummary(rule)) + '</summary>' + rankingInput(p + 'name', rankingText.ruleName, rule.name) +
   '<label class="check"><input type="checkbox" data-ranking-path="' + p + 'enabled" data-kind="checkbox"' + (rule.enabled ? ' checked' : '') + '>' + escapeHtml(rankingText.enabled) + '</label>' +
   rankingSelect(p + 'action', {BOOST: rankingText.boost, EXCLUDE: rankingText.exclude, REQUIRE: rankingText.require}, rule.action) +
   (rule.action === 'BOOST' ? rankingInput(p + 'score', rankingText.score, rule.score, 'number', 'number') : rankingInput(p + 'minimumRemaining', rankingText.minimum, rule.minimumRemaining, 'number', 'number') + '<p class="ranking-note">' + escapeHtml(rankingText.minimumHint) + '</p>') +
   rankingSelect(p + 'matchAny', {false: rankingText.all, true: rankingText.any}, rule.matchAny, 'bool-select') + conditions +
   '<button class="btn add" type="button" data-ranking-condition-add="' + i + '">' + escapeHtml(rankingText.addCondition) + '</button>' + rankingControls('rules', i, all.length) + '</details>';
 }).join('');
}
function renderRanking() {
 if (!rankingState || !rankingDraft) return;
 $('rankingName').value = rankingDraft.name;
 $('rankingLanguages').value = rankingDraft.preferredLanguages.join(', ');
 $('rankingMovieSize').value = rankingDraft.preferredMovieSizeGb;
 $('rankingEpisodeSize').value = rankingDraft.preferredEpisodeSizeGb;
 $('rankingBucket').value = rankingDraft.topPerBucket;
 const preset = rankingState.presets.find(p => p.id === $('rankingPreset').value);
 $('rankingQuickPreferences').hidden = !preset || preset.id === 'ORIGINAL';
 $('rankingDescription').textContent = preset ? preset.description : rankingText.customDesc;
 renderRankingLists();
}
async function loadRanking() {
 rankingState = await api('/api/ranking');
 rankingDraft = rankingClone(rankingState.profile);
 rankingCustomDraft = rankingState.settings.custom ? rankingClone(rankingState.settings.custom) : null;
 $('rankingPreferredLanguage').innerHTML = rankingState.languages.map(l => '<option value="' + l.code + '">' + escapeHtml(l.name) + '</option>').join('');
 $('rankingPreferredLanguage').value = rankingState.settings.preferredLanguage;
 $('rankingPreset').innerHTML = rankingState.presets.map(p => '<option value="' + p.id + '">' + escapeHtml(p.name) + '</option>').join('') + '<option value="CUSTOM">' + escapeHtml(rankingText.custom) + '</option>';
 $('rankingPreset').value = rankingState.settings.preset;
 $('rankingDirty').textContent = '';
 renderRanking();
}
function markRankingDraft() {
 $('rankingPreset').value = 'CUSTOM';
 $('rankingQuickPreferences').hidden = true;
 $('rankingDescription').textContent = rankingText.customDesc;
 $('rankingDirty').textContent = rankingText.draft;
 $('rankingResults').replaceChildren();
}
function rankingSettings() {
 if (!rankingState) throw new Error(rankingText.invalid);
 return { preset: $('rankingPreset').value, custom: $('rankingPreset').value === 'CUSTOM' ? rankingClone(rankingDraft) : rankingState.settings.custom,
  preferredLanguage: $('rankingPreferredLanguage').value };
}
function rankingPresetDraft(selected) {
 const profile = rankingClone(rankingState.presets.find(p => p.id === selected).profile);
 const language = $('rankingPreferredLanguage').value;
 if (selected !== 'ORIGINAL' && language) {
  profile.preferredLanguages = [language];
  profile.sort = [{key:'LANGUAGE', descending:true}, ...profile.sort.filter(s => s.key !== 'LANGUAGE')];
 }
 return profile;
}
$('rankingPreferredLanguage').onchange = () => {
 if (!rankingState || ['ORIGINAL', 'CUSTOM'].includes($('rankingPreset').value)) return;
 rankingDraft = rankingPresetDraft($('rankingPreset').value);
 $('rankingDirty').textContent = rankingText.draft;
 $('rankingResults').replaceChildren();
 renderRanking();
};
$('rankingPreset').onchange = () => {
 if (!rankingState) return;
 const selected = $('rankingPreset').value;
 if (selected === 'CUSTOM') rankingDraft = rankingClone(rankingCustomDraft || rankingState.presets.find(p => p.id === 'BALANCED').profile);
 else rankingDraft = rankingPresetDraft(selected);
 $('rankingRules').replaceChildren();
 $('rankingDirty').textContent = rankingText.draft;
 $('rankingResults').replaceChildren();
 renderRanking();
};
$('panel-ranking').addEventListener('input', event => {
 const input = event.target;
 const path = input.dataset.rankingPath;
 if (!path || !rankingDraft) return;
 let value = input.value;
 if (input.dataset.kind === 'number') value = value === '' ? 0 : Number(value);
 if (input.dataset.kind === 'checkbox') value = input.checked;
 if (input.dataset.kind === 'bool-select') value = value === 'true';
 if (input.dataset.kind === 'resolutions') value = rankingCsv(value).map(Number);
 if (input.dataset.kind === 'qualities') value = rankingCsv(value).map(s => s.toUpperCase());
 if (input.dataset.kind === 'languages') value = rankingCsv(value).map(s => s.toLowerCase());
 const parts = path.split('.'); let target = rankingDraft;
 parts.slice(0, -1).forEach(key => { target = target[key]; });
 target[parts[parts.length - 1]] = value;
 if (path.endsWith('.field')) {
  const numeric = ['RESOLUTION', 'SIZE_GB', 'AGE_DAYS'].includes(value);
  target.operator = numeric ? 'AT_LEAST' : 'IS';
  target.value = numeric ? '0' : '';
 }
 markRankingDraft();
 if (path.startsWith('rules.') && (path.endsWith('.name') || path.endsWith('.score') || path.endsWith('.enabled'))) {
  const summary = $('rankingRules').querySelector('details[data-rule-index="' + parts[1] + '"] > summary');
  if (summary) summary.textContent = rankingRuleSummary(rankingDraft.rules[Number(parts[1])]);
 }
 if (path.endsWith('.action') && value === 'BOOST' && !rankingDraft.sort.some(s => s.key === 'SCORE')) {
  rankingDraft.sort.splice(Math.min(1, rankingDraft.sort.length), 0, {key:'SCORE', descending:true});
 }
 rankingCustomDraft = rankingClone(rankingDraft);
 if (path.endsWith('.field') || path.endsWith('.action')) renderRankingLists();
});
$('panel-ranking').addEventListener('click', event => {
 const button = event.target.closest('button');
 if (!button || !rankingDraft) return;
 let changed = false;
 if (button.dataset.rankingMove) {
  const list = rankingDraft[button.dataset.rankingMove], i = Number(button.dataset.index), j = i + Number(button.dataset.offset);
  if (j >= 0 && j < list.length) {
   if (button.dataset.rankingMove === 'rules') {
    const a = $('rankingRules').querySelector('details[data-rule-index="' + i + '"]');
    const b = $('rankingRules').querySelector('details[data-rule-index="' + j + '"]');
    if (a) a.dataset.ruleIndex = j; if (b) b.dataset.ruleIndex = i;
   }
   [list[i], list[j]] = [list[j], list[i]]; changed = true;
  }
 }
 if (button.dataset.rankingRemove) {
  const index = Number(button.dataset.index);
  if (button.dataset.rankingRemove === 'rules') {
   $('rankingRules').querySelector('details[data-rule-index="' + index + '"]')?.remove();
   $('rankingRules').querySelectorAll('details[data-rule-index]').forEach(row => {
    if (Number(row.dataset.ruleIndex) > index) row.dataset.ruleIndex = Number(row.dataset.ruleIndex) - 1;
   });
  }
  rankingDraft[button.dataset.rankingRemove].splice(index, 1); changed = true;
 }
 if (button.dataset.rankingAdd) {
  const kind = button.dataset.rankingAdd, list = rankingDraft[kind];
  if (kind === 'sort') {
   const key = Object.keys(rankingKeys).find(k => !list.some(s => s.key === k));
   if (key) { list.push({ key, descending: true }); changed = true; }
  } else if (kind === 'tiers' && list.length < 24) {
   if (!rankingDraft.sort.some(s => s.key === 'TIER')) rankingDraft.sort.unshift({key:'TIER', descending:true});
   list.push({name: rankingText.tierName, resolutions: [], qualities: []}); changed = true;
  }
  else if (kind === 'rules' && list.length < 80) {
   if (!rankingDraft.sort.some(s => s.key === 'SCORE')) rankingDraft.sort.splice(Math.min(1, rankingDraft.sort.length), 0, {key:'SCORE', descending:true});
   rankingExpandRule = list.length; list.push({name: rankingText.ruleName, enabled: true, action: 'BOOST', score: 100, matchAny: false, minimumRemaining: 0,
   conditions: [{field: 'GROUP', operator: 'IS', value: ''}]}); changed = true; }
 }
 if (button.dataset.rankingConditionAdd !== undefined) {
  const conditions = rankingDraft.rules[Number(button.dataset.rankingConditionAdd)].conditions;
  if (conditions.length < 8) { conditions.push({field: 'TITLE', operator: 'CONTAINS', value: ''}); changed = true; }
 }
 if (button.dataset.rankingConditionRemove !== undefined) { rankingDraft.rules[Number(button.dataset.rankingConditionRemove)].conditions.splice(Number(button.dataset.index), 1); changed = true; }
 if (changed) { markRankingDraft(); rankingCustomDraft = rankingClone(rankingDraft); renderRankingLists(); }
});
$('saveRanking').onclick = event => run(event.target, ${j(R.string.web_status_saving)}, async () => {
 const r = await api('/api/ranking', rankingSettings());
 await loadRanking();
 setStatus(r.message);
});
function rankingSamples() {
 return $('rankingSamples').value.split(/\r?\n/).filter(line => line.trim()).map(line => {
  const fields = line.split('|').map(s => s.trim());
  const number = value => { if (!value) return null; const n = Number(value); if (!Number.isFinite(n)) throw new Error(rankingText.invalid); return n; };
  return { title: fields[0], sizeGb: number(fields[1]), ageDays: number(fields[2]), indexer: fields[3] || '' };
 });
}
$('previewRanking').onclick = event => run(event.target, rankingText.pending, async () => {
 const result = await api('/api/ranking/preview', {settings: rankingSettings(), samples: rankingSamples(), mediaType: $('rankingMedia').value});
 $('rankingResults').innerHTML = '<p class="ranking-note">' + escapeHtml(rankingText.shown) + ': ' + result.results.length + '</p>' + result.results.map((r, i) =>
  '<div class="ranking-result"><b>' + (i + 1) + '. ' + escapeHtml(r.title) + '</b><small>' + escapeHtml(r.tier || rankingText.unmatched) + ' · ' + escapeHtml(rankingText.score) + ': ' + r.score +
  (r.overflow ? ' · ' + escapeHtml(rankingText.more) : '') + '</small>' + (r.matches.length ? '<small>' + escapeHtml(r.matches.join(' · ')) + '</small>' : '') + '</div>').join('') +
  (result.excluded.length ? '<p class="ranking-note">' + escapeHtml(rankingText.excluded) + ': ' + result.excluded.length + '</p>' + result.excluded.map(t => '<div class="ranking-result"><small>' + escapeHtml(t) + '</small></div>').join('') : '');
 setStatus('');
 $('rankingResults').scrollIntoView({block: 'nearest', behavior: 'smooth'});
});
$('exportRanking').onclick = () => {
 if (!rankingDraft) return;
 const profile = { format: 'nuvio.stream-ranking', version: 1, profile: rankingDraft };
 const url = URL.createObjectURL(new Blob([JSON.stringify(profile, null, 2)], {type: 'application/json'}));
 const link = document.createElement('a'); link.href = url; link.download = 'nuvio-sorting-profile.json'; link.click();
 setTimeout(() => URL.revokeObjectURL(url), 1000);
};
$('rankingImportFile').onchange = async event => {
 const file = event.target.files[0];
 if (!file) return;
 try {
  if (file.size > 60000) throw new Error(rankingText.invalid);
  const input = JSON.parse(await file.text());
  if (input.format !== 'nuvio.stream-ranking' || input.version !== 1 || !input.profile || !rankingState) throw new Error(rankingText.invalid);
  const settings = {preset: 'CUSTOM', custom: input.profile};
  const normalized = await api('/api/ranking/preview', {settings, samples: [{title: 'Validation sample'}]});
  rankingDraft = rankingClone(normalized.profile);
  markRankingDraft(); rankingCustomDraft = rankingClone(rankingDraft); renderRanking();
  setStatus(rankingText.imported);
 } catch (e) { setStatus(e.message || rankingText.invalid, true); }
 finally { event.target.value = ''; }
};
""".trimIndent()
    }
    private fun String.escape() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}
