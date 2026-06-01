package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R

object DebridFormatterWebPage {
    fun html(context: Context?): String {
        val appName = context?.getString(R.string.app_name) ?: "NuvioTV"
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>$appName - Direct Debrid Settings</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap" rel="stylesheet">
<style>
  * {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
    -webkit-tap-highlight-color: transparent;
  }
  *:focus, *:active { outline: none !important; }
  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #000;
    color: #fff;
    min-height: 100vh;
    line-height: 1.5;
  }
  .page {
    max-width: 600px;
    margin: 0 auto;
    padding: 0 1.5rem 6rem;
  }
  .header {
    text-align: center;
    padding: 3rem 0 2.5rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    margin-bottom: 2.5rem;
  }
  .header-logo {
    height: 40px;
    width: auto;
    margin-bottom: 0.5rem;
    filter: brightness(0) invert(1);
    opacity: 0.9;
  }
  .header p {
    font-size: 0.875rem;
    font-weight: 300;
    color: rgba(255, 255, 255, 0.4);
    letter-spacing: 0.02em;
  }
  .intro {
    margin-bottom: 2.5rem;
  }
  .intro-title {
    font-size: 1rem;
    font-weight: 700;
    letter-spacing: -0.01em;
    margin-bottom: 0.35rem;
  }
  .intro-copy {
    color: rgba(255, 255, 255, 0.42);
    font-size: 0.875rem;
    font-weight: 300;
  }
  .tabs {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0.5rem;
    margin-bottom: 2rem;
  }
  .tab {
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 999px;
    background: transparent;
    color: rgba(255, 255, 255, 0.58);
    font-family: inherit;
    font-size: 0.875rem;
    font-weight: 600;
    padding: 0.85rem 1rem;
    cursor: pointer;
    transition: all 0.25s ease;
  }
  .tab.active {
    background: #fff;
    border-color: #fff;
    color: #000;
  }
  .panel {
    display: none;
  }
  .panel.active {
    display: block;
  }
  .section-label {
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 1rem;
  }
  .chips {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
    margin-bottom: 2.5rem;
  }
  .chip {
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 100px;
    padding: 0.45rem 0.7rem;
    color: rgba(255, 255, 255, 0.55);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.72rem;
  }
  .field {
    margin-bottom: 1.5rem;
  }
  .field label {
    display: block;
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 0.75rem;
  }
  textarea {
    width: 100%;
    min-height: 150px;
    resize: vertical;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 16px;
    padding: 0.9rem 1rem;
    color: #fff;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.82rem;
    line-height: 1.5;
    transition: border-color 0.3s ease;
  }
  textarea:focus {
    border-color: rgba(255, 255, 255, 0.4);
  }
  input, select {
    width: 100%;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 12px;
    padding: 0.75rem 0.85rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.875rem;
  }
  select option {
    color: #000;
  }
  #descriptionTemplate {
    min-height: 280px;
  }
  #badgePayload {
    min-height: 240px;
  }
  .subcopy {
    color: rgba(255, 255, 255, 0.42);
    font-size: 0.875rem;
    font-weight: 300;
    margin: -0.35rem 0 1.5rem;
  }
  .badge-summary {
    color: rgba(255, 255, 255, 0.58);
    font-size: 0.875rem;
    margin: 1rem 0;
  }
  .badge-list {
    display: grid;
    gap: 0.75rem;
    margin-top: 1rem;
  }
  .badge-row {
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 14px;
    padding: 0.9rem;
  }
  .badge-source {
    color: rgba(255, 255, 255, 0.86);
    font-size: 0.875rem;
    overflow-wrap: anywhere;
    margin-bottom: 0.45rem;
  }
  .badge-meta {
    color: rgba(255, 255, 255, 0.45);
    font-size: 0.78rem;
    margin-bottom: 0.75rem;
  }
  .badge-actions {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
  }
  .mini-btn {
    flex: 0 0 auto;
    padding: 0.5rem 0.8rem;
    font-size: 0.78rem;
  }
  .badge-preview {
    display: none;
    margin-top: 1.5rem;
    border-top: 1px solid rgba(255, 255, 255, 0.08);
    padding-top: 1.25rem;
  }
  .badge-preview.active {
    display: block;
  }
  .badge-preview-title {
    color: rgba(255, 255, 255, 0.82);
    font-size: 0.95rem;
    font-weight: 700;
    margin-bottom: 0.35rem;
  }
  .badge-preview-section {
    margin-top: 1rem;
  }
  .badge-preview-section-title {
    color: rgba(255, 255, 255, 0.7);
    font-size: 0.8rem;
    font-weight: 600;
    margin-bottom: 0.55rem;
  }
  .badge-chip-row {
    display: flex;
    gap: 0.35rem;
    flex-wrap: wrap;
  }
  .badge-chip {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    height: 24px;
    min-width: 38px;
    max-width: 112px;
    padding: 3px 4px;
    border-radius: 6px;
    overflow: hidden;
  }
  .badge-chip img {
    max-height: 18px;
    max-width: 104px;
    object-fit: contain;
    display: block;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0.75rem;
    margin-bottom: 1.5rem;
  }
  .checks {
    display: grid;
    gap: 0.5rem;
    margin-bottom: 1.5rem;
  }
  .checks-title {
    color: rgba(255, 255, 255, 0.65);
    font-size: 0.82rem;
    font-weight: 600;
    margin-bottom: 0.2rem;
  }
  .check-grid {
    display: flex;
    flex-wrap: wrap;
    gap: 0.45rem;
  }
  .check {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 999px;
    padding: 0.45rem 0.65rem;
    color: rgba(255, 255, 255, 0.68);
    font-size: 0.78rem;
  }
  .check input {
    width: auto;
    accent-color: #fff;
  }
  .actions {
    display: flex;
    gap: 0.75rem;
    margin-top: 2rem;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    flex: 1;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 100px;
    padding: 0.875rem 1.5rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.875rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.3s ease;
    white-space: nowrap;
    -webkit-tap-highlight-color: transparent;
  }
  .btn:hover {
    background: #fff;
    color: #000;
    border-color: #fff;
  }
  .btn:active { transform: scale(0.97); }
  .status {
    color: rgba(135, 239, 172, 0.95);
    font-size: 0.875rem;
    font-weight: 300;
    min-height: 24px;
    margin-top: 1rem;
    text-align: center;
  }
  .status.error {
    color: rgba(207, 102, 121, 0.9);
  }
  @media (max-width: 480px) {
    .page { padding: 0 1rem 5rem; }
    .header { padding: 2rem 0 2rem; }
    .header-logo { height: 32px; }
    .grid { grid-template-columns: 1fr; }
    .actions { flex-direction: column; }
    .tabs { grid-template-columns: 1fr; }
    .tab { padding: 0.75rem 0.6rem; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="NuvioTV" class="header-logo">
    <p>Direct Debrid Settings</p>
  </div>

  <div class="intro">
    <div class="intro-title">Customize Debrid streams</div>
    <div class="intro-copy">Adjust stream labels, filters, and sorting used for Direct Debrid results.</div>
  </div>

  <div class="tabs">
    <button class="tab active" type="button" data-tab="formatter">Formatter</button>
    <button class="tab" type="button" data-tab="rules">Filters &amp; Sort</button>
    <button class="tab" type="button" data-tab="badges">Badge URLs</button>
  </div>

  <div class="panel active" id="panel-formatter">
    <div class="section-label">Template fields</div>
    <div class="chips">
      <span class="chip">{stream.resolution}</span>
      <span class="chip">{stream.quality}</span>
      <span class="chip">{stream.visualTags::join(' | ')}</span>
      <span class="chip">{stream.audioTags::join(' | ')}</span>
      <span class="chip">{stream.rseMatched::join(' | ')}</span>
      <span class="chip">{stream.size::bytes}</span>
      <span class="chip">{service.cached::istrue["Ready"||"Not Ready"]}</span>
    </div>

    <div class="field">
      <label for="nameTemplate">Name Template</label>
      <textarea id="nameTemplate" spellcheck="false"></textarea>
    </div>

    <div class="field">
      <label for="descriptionTemplate">Description Template</label>
      <textarea id="descriptionTemplate" spellcheck="false"></textarea>
    </div>
  </div>

  <div class="panel" id="panel-rules">
    <div class="section-label">Stream rules</div>
    <div class="grid">
      <div class="field">
        <label for="maxResults">Max Results</label>
        <input id="maxResults" type="number" min="0" max="100" inputmode="numeric">
      </div>
      <div class="field">
        <label for="maxPerResolution">Per Resolution</label>
        <input id="maxPerResolution" type="number" min="0" max="100" inputmode="numeric">
      </div>
      <div class="field">
        <label for="maxPerQuality">Per Quality</label>
        <input id="maxPerQuality" type="number" min="0" max="100" inputmode="numeric">
      </div>
      <div class="field">
        <label for="sizeMinGb">Min Size GB</label>
        <input id="sizeMinGb" type="number" min="0" max="100" inputmode="numeric">
      </div>
      <div class="field">
        <label for="sizeMaxGb">Max Size GB</label>
        <input id="sizeMaxGb" type="number" min="0" max="100" inputmode="numeric">
      </div>
      <div class="field">
        <label for="sortPreset">Sort</label>
        <select id="sortPreset">
          <option value="original">Original order</option>
          <option value="bestQuality">Best quality first</option>
          <option value="largest">Largest first</option>
          <option value="smallest">Smallest first</option>
          <option value="audio">Best audio first</option>
          <option value="language">Language first</option>
        </select>
      </div>
    </div>

    <div id="streamRules"></div>

    <div class="grid">
      <div class="field">
        <label for="requiredReleaseGroups">Required Groups</label>
        <textarea id="requiredReleaseGroups" spellcheck="false"></textarea>
      </div>
      <div class="field">
        <label for="excludedReleaseGroups">Excluded Groups</label>
        <textarea id="excludedReleaseGroups" spellcheck="false"></textarea>
      </div>
    </div>
  </div>

  <div class="panel" id="panel-badges">
    <div class="section-label">Badge URLs</div>
    <div class="subcopy">Manage imported label badge JSON URLs.</div>
    <div class="field">
      <label for="badgeSource">Badge JSON URL</label>
      <textarea id="badgeSource" spellcheck="false"></textarea>
    </div>
    <div class="field">
      <label for="badgePayload">Badge JSON</label>
      <textarea id="badgePayload" spellcheck="false" placeholder="Paste badge JSON here when you do not want the TV app to fetch the URL."></textarea>
    </div>
    <div class="actions">
      <button class="btn" id="importBadges" type="button">Import</button>
      <button class="btn" id="clearBadgeDraft" type="button">Clear</button>
    </div>
    <div class="badge-summary" id="badgeSummary">Not imported</div>
    <div class="badge-list" id="badgeImports"></div>
    <div class="badge-preview" id="badgePreview"></div>
  </div>

  <div class="actions">
    <button class="btn" id="defaults">Restore Default</button>
    <button class="btn" id="save">Save Settings</button>
  </div>
  <div class="status" id="status"></div>
</div>
<script>
let defaults = null;
const nameBox = document.getElementById('nameTemplate');
const descBox = document.getElementById('descriptionTemplate');
const statusBox = document.getElementById('status');
const streamRules = document.getElementById('streamRules');
const badgeSource = document.getElementById('badgeSource');
const badgePayload = document.getElementById('badgePayload');
const badgeImports = document.getElementById('badgeImports');
const badgeSummary = document.getElementById('badgeSummary');
const badgePreview = document.getElementById('badgePreview');
let streamBadgeRules = {imports:[]};
document.querySelectorAll('.tab').forEach(tab=>{
  tab.addEventListener('click',()=>{
    const target=tab.dataset.tab;
    document.querySelectorAll('.tab').forEach(item=>item.classList.toggle('active',item===tab));
    document.querySelectorAll('.panel').forEach(panel=>panel.classList.toggle('active',panel.id==='panel-'+target));
  });
});
const options = {
  resolutions: [['P2160','2160p'],['P1440','1440p'],['P1080','1080p'],['P720','720p'],['P576','576p'],['P480','480p'],['P360','360p'],['UNKNOWN','Unknown']],
  qualities: [['BLURAY_REMUX','BluRay REMUX'],['BLURAY','BluRay'],['WEB_DL','WEB-DL'],['WEBRIP','WEBRip'],['HDRIP','HDRip'],['HD_RIP','HC HD-Rip'],['DVDRIP','DVDRip'],['HDTV','HDTV'],['CAM','CAM'],['TS','TS'],['TC','TC'],['SCR','SCR'],['UNKNOWN','Unknown']],
  visualTags: [['HDR_DV','HDR+DV'],['DV_ONLY','DV Only'],['HDR_ONLY','HDR Only'],['HDR10_PLUS','HDR10+'],['HDR10','HDR10'],['DV','DV'],['HDR','HDR'],['HLG','HLG'],['TEN_BIT','10bit'],['THREE_D','3D'],['IMAX','IMAX'],['AI','AI'],['SDR','SDR'],['H_OU','H-OU'],['H_SBS','H-SBS'],['UNKNOWN','Unknown']],
  audioTags: [['ATMOS','Atmos'],['DD_PLUS','DD+'],['DD','DD'],['DTS_X','DTS:X'],['DTS_HD_MA','DTS-HD MA'],['DTS_HD','DTS-HD'],['DTS_ES','DTS-ES'],['DTS','DTS'],['TRUEHD','TrueHD'],['OPUS','OPUS'],['FLAC','FLAC'],['AAC','AAC'],['UNKNOWN','Unknown']],
  audioChannels: [['CH_7_1','7.1'],['CH_6_1','6.1'],['CH_5_1','5.1'],['CH_2_0','2.0'],['UNKNOWN','Unknown']],
  encodes: [['AV1','AV1'],['HEVC','HEVC'],['AVC','AVC'],['XVID','XviD'],['DIVX','DivX'],['UNKNOWN','Unknown']],
  languages: [['EN','English'],['HI','Hindi'],['IT','Italian'],['ES','Spanish'],['FR','French'],['DE','German'],['PT','Portuguese'],['JA','Japanese'],['KO','Korean'],['ZH','Chinese'],['MULTI','Multi'],['UNKNOWN','Unknown']]
};
const groups = [
  ['preferredResolutions','Preferred Resolutions','resolutions'],
  ['requiredResolutions','Required Resolutions','resolutions'],
  ['excludedResolutions','Excluded Resolutions','resolutions'],
  ['preferredQualities','Preferred Qualities','qualities'],
  ['requiredQualities','Required Qualities','qualities'],
  ['excludedQualities','Excluded Qualities','qualities'],
  ['preferredVisualTags','Preferred Visual Tags','visualTags'],
  ['requiredVisualTags','Required Visual Tags','visualTags'],
  ['excludedVisualTags','Excluded Visual Tags','visualTags'],
  ['preferredAudioTags','Preferred Audio Tags','audioTags'],
  ['requiredAudioTags','Required Audio Tags','audioTags'],
  ['excludedAudioTags','Excluded Audio Tags','audioTags'],
  ['preferredAudioChannels','Preferred Channels','audioChannels'],
  ['requiredAudioChannels','Required Channels','audioChannels'],
  ['excludedAudioChannels','Excluded Channels','audioChannels'],
  ['preferredEncodes','Preferred Encodes','encodes'],
  ['requiredEncodes','Required Encodes','encodes'],
  ['excludedEncodes','Excluded Encodes','encodes'],
  ['preferredLanguages','Preferred Languages','languages'],
  ['requiredLanguages','Required Languages','languages'],
  ['excludedLanguages','Excluded Languages','languages']
];
function sortCriteriaForPreset(value){
  if(value==='bestQuality')return[{key:'RESOLUTION',direction:'DESC'},{key:'QUALITY',direction:'DESC'},{key:'VISUAL_TAG',direction:'DESC'},{key:'AUDIO_TAG',direction:'DESC'},{key:'AUDIO_CHANNEL',direction:'DESC'},{key:'ENCODE',direction:'DESC'},{key:'SIZE',direction:'DESC'}];
  if(value==='largest')return[{key:'SIZE',direction:'DESC'}];
  if(value==='smallest')return[{key:'SIZE',direction:'ASC'}];
  if(value==='audio')return[{key:'AUDIO_TAG',direction:'DESC'},{key:'AUDIO_CHANNEL',direction:'DESC'},{key:'RESOLUTION',direction:'DESC'},{key:'QUALITY',direction:'DESC'},{key:'SIZE',direction:'DESC'}];
  if(value==='language')return[{key:'LANGUAGE',direction:'DESC'},{key:'RESOLUTION',direction:'DESC'},{key:'QUALITY',direction:'DESC'},{key:'SIZE',direction:'DESC'}];
  return[];
}
function presetForSortCriteria(criteria){
  const keys=(criteria||[]).map(c=>c.key+':'+c.direction).join('|');
  if(keys==='')return'original';
  if(keys==='RESOLUTION:DESC|QUALITY:DESC|VISUAL_TAG:DESC|AUDIO_TAG:DESC|AUDIO_CHANNEL:DESC|ENCODE:DESC|SIZE:DESC')return'bestQuality';
  if(keys==='SIZE:DESC')return'largest';
  if(keys==='SIZE:ASC')return'smallest';
  if(keys.startsWith('AUDIO_TAG:DESC|AUDIO_CHANNEL:DESC'))return'audio';
  if(keys.startsWith('LANGUAGE:DESC'))return'language';
  return'original';
}
function renderRules(){
  streamRules.innerHTML=groups.map(([id,title,source])=>'<div class="checks"><div class="checks-title">'+title+'</div><div class="check-grid">'+options[source].map(([value,label])=>'<label class="check"><input type="checkbox" data-group="'+id+'" value="'+value+'"><span>'+label+'</span></label>').join('')+'</div></div>').join('');
}
function setChecks(id, values){
  const set=new Set(values||[]);
  document.querySelectorAll('[data-group="'+id+'"]').forEach(input=>{input.checked=set.has(input.value);});
}
function getChecks(id){
  return Array.from(document.querySelectorAll('[data-group="'+id+'"]:checked')).map(input=>input.value);
}
function lines(id){
  return document.getElementById(id).value.split('\\n').map(v=>v.trim()).filter(Boolean);
}
function numberValue(id){
  return Math.max(0, parseInt(document.getElementById(id).value||'0',10)||0);
}
function collectPreferences(){
  const prefs={
    maxResults:numberValue('maxResults'),
    maxPerResolution:numberValue('maxPerResolution'),
    maxPerQuality:numberValue('maxPerQuality'),
    sizeMinGb:numberValue('sizeMinGb'),
    sizeMaxGb:numberValue('sizeMaxGb'),
    sortCriteria:sortCriteriaForPreset(document.getElementById('sortPreset').value),
    requiredReleaseGroups:lines('requiredReleaseGroups'),
    excludedReleaseGroups:lines('excludedReleaseGroups')
  };
  groups.forEach(([id])=>{prefs[id]=getChecks(id);});
  return prefs;
}
function applyPreferences(prefs){
  const p=prefs||{};
  ['maxResults','maxPerResolution','maxPerQuality','sizeMinGb','sizeMaxGb'].forEach(id=>{document.getElementById(id).value=p[id]||0;});
  document.getElementById('sortPreset').value=presetForSortCriteria(p.sortCriteria);
  groups.forEach(([id])=>setChecks(id,p[id]));
  document.getElementById('requiredReleaseGroups').value=(p.requiredReleaseGroups||[]).join('\\n');
  document.getElementById('excludedReleaseGroups').value=(p.excludedReleaseGroups||[]).join('\\n');
}
function escapeHtml(value){
  return String(value||'').replace(/[&<>"']/g, ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}
function badgeColor(value){
  const hex=String(value||'').trim().replace(/^#/,'');
  if(!/^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(hex))return null;
  return '#'+hex.substring(hex.length===8?2:0);
}
function badgeRulesPreviewText(rules){
  const imports=(rules&&rules.imports)||[];
  if(!imports.length)return'Not imported';
  const active=imports.find(item=>item.isActive)||imports[0];
  const enabled=((active&&active.filters)||[]).filter(filter=>filter.isEnabled!==false).length;
  return imports.length+'/3 URLs, '+enabled+' active badges';
}
function badgePreviewSections(importItem){
  const filters=(importItem.filters||[]).filter(filter=>filter.imageURL);
  if(!filters.length)return[];
  const sections=[];
  const used=new Set();
  (importItem.groups||[]).forEach((group,index)=>{
    const groupFilters=filters.filter(filter=>filter.groupId===group.id);
    if(groupFilters.length){
      used.add(group.id);
      sections.push({id:group.id||('group-'+index),title:group.name||('Group '+(index+1)),filters:groupFilters});
    }
  });
  const other=filters.filter(filter=>!used.has(filter.groupId));
  if(other.length)sections.push({id:'other',title:'Other badges',filters:other});
  return sections;
}
function renderBadgeImports(){
  const imports=(streamBadgeRules&&streamBadgeRules.imports)||[];
  badgeSummary.textContent = imports.length ? badgeRulesPreviewText(streamBadgeRules) : 'No badge URLs imported.';
  badgeImports.innerHTML = imports.map((importItem,index)=>{
    const status=importItem.isActive?'Active':'Inactive';
    const enabled=(importItem.filters||[]).filter(filter=>filter.isEnabled!==false).length;
    const activateButton=imports.length>1?'<button class="btn mini-btn" type="button" data-badge-active="'+index+'">'+status+'</button>':'';
    return '<div class="badge-row"><div class="badge-source">'+escapeHtml(importItem.sourceUrl)+'</div><div class="badge-meta">'+status+', '+enabled+' enabled badges, '+((importItem.groups||[]).length)+' groups</div><div class="badge-actions">'+activateButton+'<button class="btn mini-btn" type="button" data-badge-preview="'+index+'">Preview</button><button class="btn mini-btn" type="button" data-badge-delete="'+index+'">Delete</button></div></div>';
  }).join('');
  document.querySelectorAll('[data-badge-active]').forEach(button=>button.addEventListener('click',()=>setActiveBadge(imports[Number(button.dataset.badgeActive)].sourceUrl)));
  document.querySelectorAll('[data-badge-delete]').forEach(button=>button.addEventListener('click',()=>deleteBadge(imports[Number(button.dataset.badgeDelete)].sourceUrl)));
  document.querySelectorAll('[data-badge-preview]').forEach(button=>button.addEventListener('click',()=>showBadgePreview(imports[Number(button.dataset.badgePreview)])));
  if(!imports.length){
    badgePreview.className='badge-preview';
    badgePreview.innerHTML='';
  }
}
function showBadgePreview(importItem){
  const sections=badgePreviewSections(importItem);
  const badgeCount=sections.reduce((sum,section)=>sum+section.filters.length,0);
  const body=sections.length?sections.map(section=>'<div class="badge-preview-section"><div class="badge-preview-section-title">'+escapeHtml(section.title)+'</div><div class="badge-chip-row">'+section.filters.map(filter=>{
    const bg=filter.tagStyle&&filter.tagStyle.toLowerCase()==='filled'?badgeColor(filter.tagColor):null;
    const border=badgeColor(filter.borderColor);
    const style=(bg?'background:'+bg+';':'')+(border?'border:1px solid '+border+';':'');
    return '<span class="badge-chip" style="'+style+'"><img src="'+escapeHtml(filter.imageURL)+'" alt="'+escapeHtml(filter.name)+'"></span>';
  }).join('')+'</div></div>').join(''):'<div class="badge-meta">No badge images in this URL.</div>';
  badgePreview.className='badge-preview active';
  badgePreview.innerHTML='<div class="badge-preview-title">Badge preview</div><div class="badge-source">'+escapeHtml(importItem.sourceUrl)+'</div><div class="badge-meta">'+badgeCount+' badges from this URL</div>'+body;
}
async function importBadges(){
  statusBox.textContent='Importing...';
  statusBox.className='status';
  const res=await fetch('/api/badges/import',{method:'POST',headers:{'Content-Type':'application/json; charset=utf-8'},body:JSON.stringify({sourceUrl:badgeSource.value,payload:badgePayload.value})});
  const body=await res.json().catch(()=>({error:'Badge import failed.'}));
  if(res.ok){
    streamBadgeRules=body.streamBadgeRules||{imports:[]};
    badgeSource.value='';
    badgePayload.value='';
    renderBadgeImports();
    statusBox.textContent='Imported badge URL.';
  }else{
    statusBox.textContent=body.error||'Badge import failed.';
    statusBox.className='status error';
  }
}
async function setActiveBadge(sourceUrl){
  const res=await fetch('/api/badges/active',{method:'POST',headers:{'Content-Type':'application/json; charset=utf-8'},body:JSON.stringify({sourceUrl})});
  const body=await res.json().catch(()=>({error:'Could not save'}));
  if(res.ok){
    streamBadgeRules=body.streamBadgeRules||streamBadgeRules;
    renderBadgeImports();
    statusBox.textContent='Saved. New streams will use these settings.';
    statusBox.className='status';
  }else{
    statusBox.textContent=body.error||'Could not save';
    statusBox.className='status error';
  }
}
async function deleteBadge(sourceUrl){
  const res=await fetch('/api/badges/delete',{method:'POST',headers:{'Content-Type':'application/json; charset=utf-8'},body:JSON.stringify({sourceUrl})});
  const body=await res.json().catch(()=>({error:'Could not save'}));
  if(res.ok){
    streamBadgeRules=body.streamBadgeRules||{imports:[]};
    renderBadgeImports();
    statusBox.textContent='Deleted badge URL.';
    statusBox.className='status';
  }else{
    statusBox.textContent=body.error||'Could not save';
    statusBox.className='status error';
  }
}
async function load(){
  const res = await fetch('/api/settings');
  const body = await res.json();
  defaults = body.defaults;
  nameBox.value = body.settings.nameTemplate ?? defaults.nameTemplate;
  descBox.value = body.settings.descriptionTemplate ?? defaults.descriptionTemplate;
  applyPreferences(body.settings.streamPreferences || defaults.streamPreferences);
  streamBadgeRules = body.settings.streamBadgeRules || defaults.streamBadgeRules || {imports:[]};
  renderBadgeImports();
}
async function save(){
  statusBox.textContent = 'Saving...';
  statusBox.className = 'status';
  const res = await fetch('/api/settings',{
    method:'POST',
    headers:{'Content-Type':'application/json; charset=utf-8'},
    body:JSON.stringify({nameTemplate:nameBox.value,descriptionTemplate:descBox.value,streamPreferences:collectPreferences(),streamBadgeRules:streamBadgeRules})
  });
  if(res.ok){
    statusBox.textContent = 'Saved. New streams will use these settings.';
  }else{
    const body = await res.json().catch(()=>({error:'Could not save'}));
    statusBox.textContent = body.error || 'Could not save';
    statusBox.className = 'status error';
  }
}
document.getElementById('save').addEventListener('click',save);
document.getElementById('defaults').addEventListener('click',()=>{
  if(!defaults)return;
  nameBox.value = defaults.nameTemplate;
  descBox.value = defaults.descriptionTemplate;
  applyPreferences(defaults.streamPreferences);
  streamBadgeRules = defaults.streamBadgeRules || {imports:[]};
  renderBadgeImports();
});
document.getElementById('importBadges').addEventListener('click',importBadges);
document.getElementById('clearBadgeDraft').addEventListener('click',()=>{badgeSource.value='';badgePayload.value='';});
renderRules();
load().catch(()=>{statusBox.textContent='Could not load Debrid settings';statusBox.className='status error';});
</script>
</body>
</html>
""".trimIndent()
    }
}
