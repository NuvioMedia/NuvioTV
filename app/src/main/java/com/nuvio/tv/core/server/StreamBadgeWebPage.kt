package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.STREAM_BADGE_IMPORT_LIMIT

object StreamBadgeWebPage {
    fun html(context: Context?): String {
        val appName = context?.getString(R.string.app_name) ?: "NuvioTV"
        val title = context?.getString(R.string.settings_stream_badges_section) ?: "Fusion Style"
        val urlsTitle = context?.getString(R.string.settings_stream_badge_urls_title) ?: "Fusion badge URLs"
        val description = context?.getString(
            R.string.settings_stream_badge_urls_description,
            STREAM_BADGE_IMPORT_LIMIT
        ) ?: "Import up to $STREAM_BADGE_IMPORT_LIMIT Fusion-style stream badge JSON URLs. Each URL can be updated or deleted separately."
        val urlLabel = context?.getString(R.string.settings_fusion_badge_url_label) ?: "Fusion badge JSON URL"
        val importAction = context?.getString(R.string.action_import) ?: "Import"
        val closeEmpty = context?.getString(R.string.settings_fusion_badges_empty) ?: "No Fusion badge URLs imported."
        val active = context?.getString(R.string.settings_fusion_badge_url_active) ?: "Active"
        val inactive = context?.getString(R.string.settings_fusion_badge_url_inactive) ?: "Inactive"
        val preview = context?.getString(R.string.settings_fusion_badge_preview_action) ?: "Preview"
        val delete = context?.getString(R.string.action_delete) ?: "Delete"
        val select = context?.getString(R.string.action_select) ?: "Select"
        val previewTitle = context?.getString(R.string.settings_fusion_badge_preview_title) ?: "Fusion badge preview"
        val previewEmpty = context?.getString(R.string.settings_fusion_badge_preview_empty) ?: "No Fusion-style badge images in this URL."
        val otherBadges = context?.getString(R.string.settings_fusion_badge_other_group_title) ?: "Other Fusion badges"
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>${appName.html()} - ${title.html()}</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #000; color: #fff; min-height: 100vh; line-height: 1.5; }
  .page { max-width: 600px; margin: 0 auto; padding: 0 1.5rem 6rem; }
  .header { text-align: center; padding: 3rem 0 2.5rem; border-bottom: 1px solid rgba(255,255,255,0.06); margin-bottom: 2.5rem; }
  .header-logo { height: 40px; width: auto; margin-bottom: 0.5rem; filter: brightness(0) invert(1); opacity: 0.9; }
  .header p { font-size: 0.875rem; font-weight: 300; color: rgba(255,255,255,0.42); letter-spacing: 0.02em; }
  .intro { margin-bottom: 2rem; }
  .intro-title { font-size: 1rem; font-weight: 700; margin-bottom: 0.35rem; }
  .intro-copy { color: rgba(255,255,255,0.44); font-size: 0.875rem; font-weight: 300; }
  .field { margin-bottom: 1rem; }
  .field label { display: block; font-size: 0.75rem; font-weight: 500; color: rgba(255,255,255,0.35); letter-spacing: 0.1em; text-transform: uppercase; margin-bottom: 0.75rem; }
  textarea { width: 100%; min-height: 90px; resize: vertical; background: transparent; border: 1px solid rgba(255,255,255,0.14); border-radius: 16px; padding: 0.9rem 1rem; color: #fff; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.82rem; line-height: 1.5; }
  textarea:focus { outline: none; border-color: rgba(255,255,255,0.42); }
  .actions { display: flex; gap: 0.75rem; margin-top: 1.25rem; }
  .btn { display: inline-flex; align-items: center; justify-content: center; gap: 0.5rem; flex: 1; background: transparent; border: 1px solid rgba(255,255,255,0.2); border-radius: 100px; padding: 0.875rem 1.3rem; color: #fff; font-family: inherit; font-size: 0.875rem; font-weight: 500; cursor: pointer; transition: all 0.25s ease; white-space: nowrap; }
  .btn:hover { background: #fff; color: #000; border-color: #fff; }
  .mini-btn { flex: 0 0 auto; padding: 0.5rem 0.8rem; font-size: 0.78rem; }
  .summary { color: rgba(255,255,255,0.6); font-size: 0.875rem; margin: 1.25rem 0; }
  .list { display: grid; gap: 0.75rem; margin-top: 1rem; }
  .row { border: 1px solid rgba(255,255,255,0.12); border-radius: 14px; padding: 0.9rem; background: rgba(255,255,255,0.02); }
  .source { color: rgba(255,255,255,0.88); font-size: 0.875rem; overflow-wrap: anywhere; margin-bottom: 0.45rem; }
  .meta { color: rgba(255,255,255,0.48); font-size: 0.78rem; margin-bottom: 0.75rem; }
  .row-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; }
  .preview { display: none; margin-top: 1.5rem; border-top: 1px solid rgba(255,255,255,0.08); padding-top: 1.25rem; }
  .preview.active { display: block; }
  .preview-title { color: rgba(255,255,255,0.84); font-size: 0.95rem; font-weight: 700; margin-bottom: 0.35rem; }
  .preview-section { margin-top: 1rem; }
  .preview-section-title { color: rgba(255,255,255,0.72); font-size: 0.8rem; font-weight: 600; margin-bottom: 0.55rem; }
  .badge-row { display: flex; gap: 0.35rem; flex-wrap: wrap; }
  .badge-chip { display: inline-flex; align-items: center; justify-content: center; height: 24px; min-width: 38px; max-width: 112px; padding: 3px 4px; border-radius: 6px; overflow: hidden; }
  .badge-chip img { max-height: 18px; max-width: 104px; object-fit: contain; display: block; }
  .status { color: rgba(135,239,172,0.95); font-size: 0.875rem; font-weight: 300; min-height: 24px; margin-top: 1rem; text-align: center; }
  .status.error { color: rgba(207,102,121,0.9); }
  @media (max-width: 480px) { .page { padding: 0 1rem 5rem; } .header { padding: 2rem 0; } .header-logo { height: 32px; } .actions { flex-direction: column; } }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="${appName.html()}" class="header-logo">
    <p>${title.html()}</p>
  </div>
  <div class="intro">
    <div class="intro-title">${urlsTitle.html()}</div>
    <div class="intro-copy">${description.html()}</div>
  </div>
  <div class="field">
    <label for="badgeSource">${urlLabel.html()}</label>
    <textarea id="badgeSource" spellcheck="false"></textarea>
  </div>
  <div class="actions">
    <button class="btn" id="importBadges" type="button">${importAction.html()}</button>
  </div>
  <div class="summary" id="badgeSummary">${closeEmpty.html()}</div>
  <div class="list" id="badgeImports"></div>
  <div class="preview" id="badgePreview"></div>
  <div class="status" id="status"></div>
</div>
<script>
const badgeSource = document.getElementById('badgeSource');
const badgeImports = document.getElementById('badgeImports');
const badgeSummary = document.getElementById('badgeSummary');
const badgePreview = document.getElementById('badgePreview');
const statusBox = document.getElementById('status');
let streamBadgeRules = {imports:[]};
const labels = {
  empty: '${closeEmpty.js()}',
  active: '${active.js()}',
  inactive: '${inactive.js()}',
  select: '${select.js()}',
  preview: '${preview.js()}',
  delete: '${delete.js()}',
  previewTitle: '${previewTitle.js()}',
  previewEmpty: '${previewEmpty.js()}',
  otherBadges: '${otherBadges.js()}'
};
function escapeHtml(value){
  return String(value||'').replace(/[&<>"']/g, ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
}
function badgeColor(value){
  const hex=String(value||'').trim().replace(/^#/,'');
  if(!/^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/.test(hex))return null;
  return '#'+hex.substring(hex.length===8?2:0);
}
function isImportActive(importItem){
  if(!importItem)return false;
  if(importItem.isActive===false||importItem.active===false)return false;
  return importItem.isActive===true||importItem.active===true||(!('isActive' in importItem)&&!('active' in importItem));
}
function normalizeBadgeRules(rules){
  const imports=((rules&&rules.imports)||[]).map(importItem=>Object.assign({},importItem,{isActive:isImportActive(importItem)}));
  return Object.assign({},rules||{},{imports});
}
function badgeRulesPreviewText(rules){
  const imports=normalizeBadgeRules(rules).imports;
  if(!imports.length)return labels.empty;
  const active=imports.find(isImportActive)||imports[0];
  const enabled=((active&&active.filters)||[]).filter(filter=>filter.isEnabled!==false).length;
  return imports.length+'/${STREAM_BADGE_IMPORT_LIMIT} URLs, '+enabled+' active Fusion badges';
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
  if(other.length)sections.push({id:'other',title:labels.otherBadges,filters:other});
  return sections;
}
function renderBadgeImports(){
  streamBadgeRules=normalizeBadgeRules(streamBadgeRules);
  const imports=streamBadgeRules.imports;
  badgeSummary.textContent = badgeRulesPreviewText(streamBadgeRules);
  badgeImports.innerHTML = imports.map((importItem,index)=>{
    const activeState=isImportActive(importItem);
    const status=activeState?labels.active:labels.inactive;
    const enabled=(importItem.filters||[]).filter(filter=>filter.isEnabled!==false).length;
    const activateButton=imports.length>1&&!activeState?'<button class="btn mini-btn" type="button" data-badge-active="'+index+'">'+labels.select+'</button>':'';
    return '<div class="row"><div class="source">'+escapeHtml(importItem.sourceUrl)+'</div><div class="meta">'+status+', '+enabled+' enabled badges, '+((importItem.groups||[]).length)+' groups</div><div class="row-actions">'+activateButton+'<button class="btn mini-btn" type="button" data-badge-preview="'+index+'">'+labels.preview+'</button><button class="btn mini-btn" type="button" data-badge-delete="'+index+'">'+labels.delete+'</button></div></div>';
  }).join('');
  document.querySelectorAll('[data-badge-active]').forEach(button=>button.addEventListener('click',()=>setActiveBadge(imports[Number(button.dataset.badgeActive)].sourceUrl)));
  document.querySelectorAll('[data-badge-delete]').forEach(button=>button.addEventListener('click',()=>deleteBadge(imports[Number(button.dataset.badgeDelete)].sourceUrl)));
  document.querySelectorAll('[data-badge-preview]').forEach(button=>button.addEventListener('click',()=>showBadgePreview(imports[Number(button.dataset.badgePreview)])));
  if(!imports.length){
    badgePreview.className='preview';
    badgePreview.innerHTML='';
  }
}
function showBadgePreview(importItem){
  const sections=badgePreviewSections(importItem);
  const badgeCount=sections.reduce((sum,section)=>sum+section.filters.length,0);
  const body=sections.length?sections.map(section=>'<div class="preview-section"><div class="preview-section-title">'+escapeHtml(section.title)+'</div><div class="badge-row">'+section.filters.map(filter=>{
    const bg=filter.tagStyle&&filter.tagStyle.toLowerCase()==='filled'?badgeColor(filter.tagColor):null;
    const border=badgeColor(filter.borderColor);
    const style=(bg?'background:'+bg+';':'')+(border?'border:1px solid '+border+';':'');
    return '<span class="badge-chip" style="'+style+'"><img src="'+escapeHtml(filter.imageURL)+'" alt="'+escapeHtml(filter.name)+'"></span>';
  }).join('')+'</div></div>').join(''):'<div class="meta">'+labels.previewEmpty+'</div>';
  badgePreview.className='preview active';
  badgePreview.innerHTML='<div class="preview-title">'+labels.previewTitle+'</div><div class="source">'+escapeHtml(importItem.sourceUrl)+'</div><div class="meta">'+badgeCount+' Fusion-style badges from this URL</div>'+body;
}
async function importBadges(){
  statusBox.textContent='Importing...';
  statusBox.className='status';
  const res=await fetch('/api/badges/import',{method:'POST',headers:{'Content-Type':'application/json; charset=utf-8'},body:JSON.stringify({sourceUrl:badgeSource.value})});
  const body=await res.json().catch(()=>({error:'Badge import failed.'}));
  if(res.ok){
    streamBadgeRules=normalizeBadgeRules(body.streamBadgeRules||{imports:[]});
    badgeSource.value='';
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
    streamBadgeRules=normalizeBadgeRules(body.streamBadgeRules||streamBadgeRules);
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
    streamBadgeRules=normalizeBadgeRules(body.streamBadgeRules||{imports:[]});
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
  streamBadgeRules = normalizeBadgeRules(body.settings.streamBadgeRules || {imports:[]});
  renderBadgeImports();
}
document.getElementById('importBadges').addEventListener('click',importBadges);
load().catch(()=>{statusBox.textContent='Could not load stream badge settings';statusBox.className='status error';});
</script>
</body>
</html>
""".trimIndent()
    }

    private fun String.html(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.js(): String =
        replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
}
