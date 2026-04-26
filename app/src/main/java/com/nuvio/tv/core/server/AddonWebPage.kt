package com.nuvio.tv.core.server

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.R
import java.util.Locale

object AddonWebPage {

    fun getHtml(
        baseContext: Context,
        webConfigMode: AddonConfigServer.WebConfigMode = AddonConfigServer.WebConfigMode.FULL
    ): String {
        val tag = baseContext.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        val context = if (!tag.isNullOrEmpty()) {
            val config = Configuration(baseContext.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            baseContext.createConfigurationContext(config)
        } else baseContext
        val isCollectionsOnly = webConfigMode == AddonConfigServer.WebConfigMode.COLLECTIONS_ONLY
        val pageTitle = if (isCollectionsOnly) {
            context.getString(R.string.web_manage_collections_title)
        } else {
            context.getString(R.string.web_manage_addons_title)
        }
        val pageSubtitle = if (isCollectionsOnly) {
            context.getString(R.string.web_manage_collections_subtitle)
        } else {
            context.getString(R.string.web_manage_addons_subtitle)
        }
        val successStatusMessage = if (isCollectionsOnly) {
            context.getString(R.string.web_status_msg_collections_updated)
        } else {
            context.getString(R.string.web_status_msg_addon_updated)
        }
        val allowAddonManagement = webConfigMode.allowAddonManagement
        val allowCatalogManagement = webConfigMode.allowCatalogManagement
        val defaultTab = when {
            allowAddonManagement -> "addons"
            allowCatalogManagement -> "catalogs"
            else -> "collections"
        }
        val tabsHtml = if (isCollectionsOnly) {
            """
  <div class="tabs">
    <button class="tab active" type="button" onclick="switchTab('collections')">${context.getString(R.string.web_tab_collections)}</button>
  </div>
"""
        } else {
            """
  <div class="tabs">
    <button class="tab active" type="button" onclick="switchTab('addons')">${context.getString(R.string.web_tab_addons)}</button>
    <button class="tab" type="button" onclick="switchTab('catalogs')">${context.getString(R.string.web_tab_home_layout)}</button>
    <button class="tab" type="button" onclick="switchTab('collections')">${context.getString(R.string.web_tab_collections)}</button>
  </div>
"""
        }
        val addonsTabHtml = if (allowAddonManagement) {
            """
  <div class="tab-content active" id="tab-addons">
    <div class="add-section">
      <label>${context.getString(R.string.web_add_addon_url)}</label>
      <div class="add-row">
        <input type="url" id="addonUrl" placeholder="${context.getString(R.string.web_placeholder_url)}" autocomplete="off" autocapitalize="off" spellcheck="false">
        <button class="btn" id="addBtn" onclick="addAddon()">${context.getString(R.string.web_btn_add)}</button>
      </div>
      <div class="add-error" id="addError"></div>
    </div>

    <div class="section-label">${context.getString(R.string.web_installed_addons)}</div>
    <ul class="addon-list" id="addonList"></ul>
    <div class="empty-state" id="emptyState">${context.getString(R.string.web_no_addons)}</div>
  </div>
"""
        } else {
            ""
        }
        val catalogsTabHtml = if (allowCatalogManagement) {
            """
  <div class="tab-content" id="tab-catalogs">
    <div class="section-block">
      <div class="section-label">${context.getString(R.string.web_home_catalogs)}</div>
      <div class="add-section" style="display:flex;gap:0.5rem">
        <button class="btn" onclick="enableAllCatalogs()" style="flex:1">${context.getString(R.string.web_btn_enable_all)}</button>
        <button class="btn" onclick="disableAllCatalogs()" style="flex:1">${context.getString(R.string.web_btn_disable_all)}</button>
      </div>
      <ul class="addon-list" id="catalogList"></ul>
      <div class="empty-state" id="catalogEmptyState">${context.getString(R.string.web_no_catalogs)}</div>
    </div>
  </div>
"""
        } else {
            ""
        }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>${context.getString(R.string.app_name)} - $pageTitle</title>
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
  .add-section {
    margin-bottom: 2.5rem;
  }
  .add-section label {
    display: block;
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 0.75rem;
  }
  .add-row {
    display: flex;
    gap: 0.75rem;
  }
  .add-row input {
    flex: 1;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 100px;
    padding: 0.875rem 1.25rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.9rem;
    font-weight: 400;
    transition: border-color 0.3s ease;
  }
  .add-row input:focus {
    border-color: rgba(255, 255, 255, 0.4);
  }
  .add-row input::placeholder {
    color: rgba(255, 255, 255, 0.2);
  }
  .add-error {
    color: rgba(207, 102, 121, 0.9);
    font-size: 0.8rem;
    margin-top: 0.75rem;
    display: none;
    padding-left: 1.25rem;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
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
  .btn-save {
    width: 100%;
    padding: 1rem;
    font-size: 0.95rem;
    font-weight: 600;
    margin-top: 2rem;
  }
  .btn-save:disabled {
    opacity: 0.2;
    cursor: not-allowed;
    pointer-events: none;
  }
  .btn-remove {
    border-color: rgba(207, 102, 121, 0.3);
    color: rgba(207, 102, 121, 0.8);
    padding: 0.5rem 1rem;
    font-size: 0.75rem;
  }
  .btn-remove:hover {
    background: rgba(207, 102, 121, 0.15);
    border-color: rgba(207, 102, 121, 0.5);
    color: #CF6679;
  }
  .btn-toggle {
    padding: 0.5rem 1rem;
    font-size: 0.75rem;
    border-color: rgba(255, 255, 255, 0.2);
    color: rgba(255, 255, 255, 0.85);
  }
  .btn-toggle:hover {
    background: rgba(255, 255, 255, 0.08);
    color: #fff;
    border-color: rgba(255, 255, 255, 0.35);
  }
  .btn-toggle.disabled {
    border-color: rgba(207, 102, 121, 0.35);
    color: rgba(207, 102, 121, 0.95);
  }
  .btn-toggle.disabled:hover {
    background: rgba(207, 102, 121, 0.15);
    border-color: rgba(207, 102, 121, 0.55);
    color: #CF6679;
  }
  .section-label {
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 1rem;
  }
  .addon-list {
    list-style: none;
  }
  .addon-item {
    border-top: 1px solid rgba(255, 255, 255, 0.06);
    padding: 1rem 0;
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }
  .addon-item:last-child {
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  }
  .addon-order {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0.2rem;
    flex-shrink: 0;
  }
  .btn-order {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 6px;
    color: rgba(255, 255, 255, 0.4);
    font-size: 0.7rem;
    cursor: pointer;
    transition: all 0.2s ease;
    padding: 0;
    -webkit-tap-highlight-color: transparent;
  }
  .btn-order:hover {
    background: rgba(255, 255, 255, 0.08);
    border-color: rgba(255, 255, 255, 0.25);
    color: #fff;
  }
  .btn-order:active {
    transform: scale(0.9);
  }
  .btn-order:disabled {
    opacity: 0.15;
    cursor: not-allowed;
    pointer-events: none;
  }
  .addon-info {
    flex: 1;
    min-width: 0;
  }
  .addon-name {
    font-size: 0.95rem;
    font-weight: 600;
    letter-spacing: -0.01em;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .addon-url {
    font-size: 0.75rem;
    color: rgba(255, 255, 255, 0.25);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 0.15rem;
  }
  .addon-desc {
    font-size: 0.8rem;
    color: rgba(255, 255, 255, 0.4);
    margin-top: 0.15rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .addon-actions {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }
  .badge-new {
    display: inline-block;
    font-size: 0.6rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: #000;
    background: #fff;
    padding: 0.15rem 0.4rem;
    border-radius: 100px;
    margin-left: 0.5rem;
    vertical-align: middle;
  }
  .empty-state {
    text-align: center;
    color: rgba(255, 255, 255, 0.2);
    padding: 3rem 0;
    font-size: 0.875rem;
    font-weight: 300;
    display: none;
  }
  .section-block {
    margin-top: 2rem;
  }
  .catalog-info {
    flex: 1;
    min-width: 0;
  }
  .catalog-name {
    font-size: 0.95rem;
    font-weight: 600;
    letter-spacing: -0.01em;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .catalog-meta {
    font-size: 0.75rem;
    color: rgba(255, 255, 255, 0.35);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    margin-top: 0.15rem;
  }
  .badge-disabled {
    display: inline-block;
    font-size: 0.6rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: rgba(207, 102, 121, 0.95);
    border: 1px solid rgba(207, 102, 121, 0.35);
    padding: 0.12rem 0.45rem;
    border-radius: 100px;
    margin-left: 0.5rem;
    vertical-align: middle;
  }
  .badge-collection {
    display: inline-block;
    font-size: 0.6rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: rgba(130, 170, 255, 0.95);
    border: 1px solid rgba(130, 170, 255, 0.35);
    padding: 0.12rem 0.45rem;
    border-radius: 100px;
    margin-left: 0.5rem;
    vertical-align: middle;
  }
  .status-overlay {
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    background: rgba(0, 0, 0, 0.92);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    z-index: 500;
    display: flex;
    align-items: center;
    justify-content: center;
    opacity: 0;
    visibility: hidden;
    transition: all 0.3s ease;
  }
  .status-overlay.visible {
    opacity: 1;
    visibility: visible;
  }
  .status-content {
    text-align: center;
    max-width: 340px;
    padding: 2rem;
  }
  .status-icon {
    margin-bottom: 1.5rem;
  }
  .spinner {
    width: 40px;
    height: 40px;
    border: 2px solid rgba(255, 255, 255, 0.1);
    border-top-color: #fff;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    margin: 0 auto;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .status-title {
    font-size: 1.25rem;
    font-weight: 700;
    letter-spacing: -0.02em;
    margin-bottom: 0.5rem;
  }
  .status-message {
    font-size: 0.875rem;
    font-weight: 300;
    color: rgba(255, 255, 255, 0.4);
    line-height: 1.6;
  }
  .status-success .status-title { color: #fff; }
  .status-rejected .status-title { color: rgba(207, 102, 121, 0.9); }
  .status-error .status-title { color: rgba(207, 102, 121, 0.9); }
  .status-dismiss {
    margin-top: 1.5rem;
  }
  .status-svg {
    width: 40px;
    height: 40px;
    margin: 0 auto;
  }
  .status-svg svg {
    width: 40px;
    height: 40px;
  }
  .connection-bar {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    background: rgba(207, 102, 121, 0.15);
    border-bottom: 1px solid rgba(207, 102, 121, 0.3);
    padding: 0.75rem 1.5rem;
    text-align: center;
    font-size: 0.8rem;
    font-weight: 500;
    color: rgba(207, 102, 121, 0.9);
    z-index: 600;
    display: none;
  }
  .connection-bar.visible {
    display: block;
  }
  .tabs {
    display: flex;
    gap: 0;
    margin-bottom: 2.5rem;
     position: sticky;
     top: 0;
     z-index: 40;
     background: rgba(6, 8, 14, 0.92);
     backdrop-filter: blur(18px);
    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  }
  .tab {
    flex: 1;
    background: transparent;
    border: none;
    border-bottom: 2px solid transparent;
    color: rgba(255, 255, 255, 0.35);
    font-family: inherit;
    font-size: 0.85rem;
    font-weight: 500;
    padding: 0.875rem 0.5rem;
    cursor: pointer;
    transition: all 0.2s ease;
    text-align: center;
    -webkit-tap-highlight-color: transparent;
  }
  .tab:hover { color: rgba(255, 255, 255, 0.6); }
  .tab.active {
    color: #fff;
    border-bottom-color: #fff;
  }
  .tab-content { display: none; }
  .tab-content.active { display: block; }
  /* ── Collection cards ── */
  .collection-card {
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.07);
    border-radius: 16px;
    margin-bottom: 1rem;
    overflow: hidden;
    transition: opacity 0.2s;
  }
  .collection-disabled { opacity: 0.4; }
  .collection-header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.875rem 1rem;
  }
  .collection-title-input {
    flex: 1;
    background: transparent;
    border: none;
    border-bottom: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 0;
    padding: 0.25rem 0;
    color: #fff;
    font-family: inherit;
    font-size: 1rem;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  .collection-title-input:focus { border-bottom-color: rgba(255, 255, 255, 0.5); outline: none; }
  .collection-title-input::placeholder { color: rgba(255,255,255,0.2); }
  .col-actions {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    flex-shrink: 0;
  }
  .col-meta-row {
    display: flex;
    align-items: center;
    padding: 0 1rem;
    gap: 0.5rem;
  }
  .col-meta-label {
    font-size: 0.7rem;
    font-weight: 500;
    color: rgba(255,255,255,0.3);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    min-width: 60px;
    flex-shrink: 0;
  }
  .folder-summary {
    font-size: 0.75rem;
    color: rgba(255, 255, 255, 0.25);
    padding: 0 1rem 0.75rem;
  }
  .badge-collection-disabled {
    font-size: 0.6rem;
    font-weight: 700;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: rgba(207, 102, 121, 0.9);
    background: rgba(207, 102, 121, 0.12);
    padding: 0.2rem 0.5rem;
    border-radius: 100px;
    flex-shrink: 0;
  }
  .collapse-header { cursor: pointer; -webkit-tap-highlight-color: transparent; user-select: none; }
  .collapse-arrow {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    transition: transform 0.25s ease;
    font-size: 0.8rem;
    color: rgba(255,255,255,0.25);
    flex-shrink: 0;
  }
  .collapse-arrow.open { transform: rotate(90deg); }

  /* ── Collection settings section ── */
  .col-settings {
    padding: 0.5rem 1rem 0.75rem;
    display: flex;
    flex-direction: column;
    gap: 0.6rem;
    border-top: 1px solid rgba(255,255,255,0.05);
  }
  .col-setting-row {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    min-height: 36px;
  }
  .col-setting-row select, .col-setting-row input[type="url"] {
    flex: 1;
    background: rgba(255,255,255,0.05);
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 8px;
    padding: 0.45rem 0.6rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.8rem;
    min-width: 0;
  }
  .col-setting-row select:focus, .col-setting-row input:focus {
    border-color: rgba(255,255,255,0.25);
    outline: none;
  }
  .col-setting-row select option { background: #111; color: #fff; }
  .col-setting-row img {
    width: 44px;
    height: 25px;
    object-fit: cover;
    border-radius: 4px;
    border: 1px solid rgba(255,255,255,0.08);
    flex-shrink: 0;
  }

  /* ── Toggle switch (replaces checkboxes) ── */
  .toggle-switch {
    position: relative;
    width: 40px;
    height: 22px;
    flex-shrink: 0;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  .toggle-switch input { opacity: 0; width: 0; height: 0; position: absolute; }
  .toggle-track {
    position: absolute;
    inset: 0;
    background: rgba(255,255,255,0.12);
    border-radius: 11px;
    transition: background 0.2s;
  }
  .toggle-switch input:checked + .toggle-track { background: rgba(255,255,255,0.85); }
  .toggle-thumb {
    position: absolute;
    top: 2px;
    left: 2px;
    width: 18px;
    height: 18px;
    background: #000;
    border-radius: 50%;
    transition: transform 0.2s;
    box-shadow: 0 1px 3px rgba(0,0,0,0.4);
  }
  .toggle-switch input:checked ~ .toggle-thumb { transform: translateX(18px); background: #000; }
  .toggle-switch:not(:has(input:checked)) .toggle-thumb { background: rgba(255,255,255,0.6); }
  .toggle-label {
    font-size: 0.8rem;
    color: rgba(255,255,255,0.5);
    flex: 1;
  }

  /* ── Folder cards ── */
  .folder-card {
    background: rgba(255,255,255,0.025);
    border-top: 1px solid rgba(255,255,255,0.05);
    padding: 0;
    margin: 0;
  }
  .folder-card:last-of-type { border-bottom: 1px solid rgba(255,255,255,0.05); }
  .folder-header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.65rem 1rem;
  }
  .folder-title-input {
    flex: 1;
    background: transparent;
    border: none;
    border-bottom: 1px solid rgba(255,255,255,0.08);
    border-radius: 0;
    padding: 0.2rem 0;
    color: #fff;
    font-family: inherit;
    font-size: 0.875rem;
    font-weight: 500;
  }
  .folder-title-input:focus { border-bottom-color: rgba(255,255,255,0.4); outline: none; }
  .folder-title-input::placeholder { color: rgba(255,255,255,0.2); }

  /* ── Folder expanded settings ── */
  .folder-settings {
    padding: 0.75rem 1rem 1rem;
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }
  .folder-settings-group {
    background: rgba(255,255,255,0.03);
    border-radius: 10px;
    overflow: hidden;
  }
  .folder-settings-group-label {
    font-size: 0.65rem;
    font-weight: 600;
    color: rgba(255,255,255,0.25);
    text-transform: uppercase;
    letter-spacing: 0.08em;
    padding: 0.6rem 0.75rem 0.35rem;
  }
  .folder-setting-item {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 0.75rem;
    min-height: 40px;
  }
  .folder-setting-item + .folder-setting-item {
    border-top: 1px solid rgba(255,255,255,0.04);
  }
  .folder-setting-item select, .folder-setting-item input[type="url"] {
    flex: 1;
    background: rgba(255,255,255,0.05);
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 8px;
    padding: 0.4rem 0.55rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.8rem;
    min-width: 0;
  }
  .folder-setting-item select:focus, .folder-setting-item input:focus {
    border-color: rgba(255,255,255,0.25);
    outline: none;
  }
  .folder-setting-item select option { background: #111; color: #fff; }
  .folder-setting-item img {
    width: 32px;
    height: 32px;
    object-fit: cover;
    border-radius: 6px;
    border: 1px solid rgba(255,255,255,0.08);
    flex-shrink: 0;
  }
  .folder-setting-label {
    font-size: 0.8rem;
    color: rgba(255,255,255,0.45);
    min-width: 55px;
    flex-shrink: 0;
  }

  /* ── Cover mode picker ── */
  .cover-mode-picker {
    display: flex;
    gap: 0;
    border-radius: 8px;
    overflow: hidden;
    border: 1px solid rgba(255,255,255,0.08);
    flex: 1;
  }
  .cover-mode-btn {
    flex: 1;
    background: transparent;
    border: none;
    color: rgba(255,255,255,0.4);
    font-family: inherit;
    font-size: 0.75rem;
    font-weight: 500;
    padding: 0.45rem 0.5rem;
    cursor: pointer;
    transition: all 0.2s;
    -webkit-tap-highlight-color: transparent;
  }
  .cover-mode-btn + .cover-mode-btn { border-left: 1px solid rgba(255,255,255,0.06); }
  .cover-mode-btn.active {
    background: rgba(255,255,255,0.1);
    color: #fff;
  }
  .cover-mode-btn:hover:not(.active) { background: rgba(255,255,255,0.05); }

  /* ── Emoji picker ── */
  .emoji-picker-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 36px;
    height: 36px;
    background: rgba(255,255,255,0.05);
    border: 1px solid rgba(255,255,255,0.1);
    border-radius: 8px;
    font-size: 1.2rem;
    cursor: pointer;
    transition: all 0.2s;
    padding: 0 0.4rem;
    -webkit-tap-highlight-color: transparent;
  }
  .emoji-picker-btn:hover { border-color: rgba(255,255,255,0.25); background: rgba(255,255,255,0.08); }
  .emoji-grid-wrap {
    display: none;
    margin-top: 0.5rem;
    background: rgba(255,255,255,0.04);
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 10px;
    padding: 0.6rem;
  }
  .emoji-grid-wrap.open { display: block; }
  .emoji-grid-search {
    width: 100%;
    background: rgba(255,255,255,0.05);
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 8px;
    padding: 0.5rem 0.65rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.8rem;
    margin-bottom: 0.5rem;
  }
  .emoji-grid-search:focus { border-color: rgba(255,255,255,0.25); outline: none; }
  .emoji-grid { max-height: 220px; overflow-y: auto; }
  .emoji-grid [data-cat] {
    display: grid;
    grid-template-columns: repeat(8, 1fr);
    gap: 2px;
  }
  .emoji-cat-label { grid-column: 1 / -1; }
  .emoji-cell {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    aspect-ratio: 1;
    font-size: 1.25rem;
    cursor: pointer;
    border-radius: 8px;
    transition: background 0.15s;
    border: none;
    background: transparent;
    -webkit-tap-highlight-color: transparent;
  }
  .emoji-cell:hover { background: rgba(255,255,255,0.1); }

  /* ── Catalog sources ── */
  .source-item {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    padding: 0.4rem 0.75rem;
    font-size: 0.78rem;
    color: rgba(255,255,255,0.55);
  }
  .source-item + .source-item { border-top: 1px solid rgba(255,255,255,0.04); }
  .source-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .source-search-input {
    width: 100%;
    background: rgba(255,255,255,0.05);
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 8px;
    padding: 0.5rem 0.65rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.8rem;
    margin-bottom: 0.25rem;
  }
  .source-search-input:focus { border-color: rgba(255,255,255,0.25); outline: none; }

  /* ── Shared small buttons ── */
  .btn-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    background: transparent;
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 6px;
    color: rgba(255,255,255,0.35);
    cursor: pointer;
    transition: all 0.2s;
    padding: 0;
    flex-shrink: 0;
    -webkit-tap-highlight-color: transparent;
  }
  .btn-icon:hover { background: rgba(255,255,255,0.08); border-color: rgba(255,255,255,0.2); color: #fff; }
  .btn-icon:disabled { opacity: 0.15; cursor: not-allowed; pointer-events: none; }
  .btn-icon.danger { border-color: rgba(207,102,121,0.25); color: rgba(207,102,121,0.6); }
  .btn-icon.danger:hover { background: rgba(207,102,121,0.12); color: #CF6679; }
  .sources-filtering .btn-icon:not(.danger) { opacity: 0.15; pointer-events: none; }
  .import-overlay {
    position: fixed;
    top: 0; left: 0; width: 100%; height: 100%;
    background: rgba(0, 0, 0, 0.92);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    z-index: 500;
    display: none;
    align-items: center;
    justify-content: center;
    padding: 1.5rem;
  }
  .import-overlay.visible { display: flex; }
  .import-modal {
    background: #111;
    border: 1px solid rgba(255, 255, 255, 0.1);
    border-radius: 16px;
    padding: 1.5rem;
    width: 100%;
    max-width: 500px;
  }
  .import-tab { display: none; }
  .import-tab.active { display: block; }
  .import-tab-btn { font-size: 0.8rem !important; padding: 0.5rem 1rem !important; flex: 1; }
  .import-tab-btn.active { background: rgba(255,255,255,0.1); border-color: rgba(255,255,255,0.3); }
  @media (max-width: 480px) {
    .page { padding: 0 1rem 5rem; }
    .header { padding: 2rem 0 2rem; }
    .header-logo { height: 32px; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="NuvioTV" class="header-logo">
    <p>$pageSubtitle</p>
  </div>

  $tabsHtml

  $addonsTabHtml

  $catalogsTabHtml

  <div class="tab-content${if (defaultTab == "collections") " active" else ""}" id="tab-collections">
    <div class="section-block">
      <div class="section-label">${context.getString(R.string.web_tab_collections)}</div>
      <div class="add-section" style="display:flex;gap:0.5rem">
        <button class="btn" onclick="enableAllCollections()" style="flex:1">${context.getString(R.string.web_btn_show_all)}</button>
        <button class="btn" onclick="disableAllCollections()" style="flex:1">${context.getString(R.string.web_btn_hide_all)}</button>
      </div>
      <div class="add-section" style="display:flex;gap:0.5rem">
        <button class="btn" onclick="addCollection()" style="flex:1">${context.getString(R.string.web_btn_new_collection)}</button>
        <button class="btn" onclick="exportCollections()" style="flex:1">${context.getString(R.string.web_btn_export)}</button>
        <button class="btn" onclick="showImportModal()" style="flex:1">${context.getString(R.string.web_btn_import)}</button>
      </div>
      <div id="collectionsList"></div>
      <div class="empty-state" id="collectionsEmptyState">${context.getString(R.string.web_no_collections)}</div>
    </div>
  </div>

  <div class="import-overlay" id="importOverlay">
    <div class="import-modal">
      <div style="font-size:1.1rem;font-weight:700;margin-bottom:1rem">${context.getString(R.string.web_import_collections_title)}</div>
      <div style="display:flex;gap:0.5rem;margin-bottom:1rem">
        <button class="btn import-tab-btn active" onclick="switchImportTab('paste')">${context.getString(R.string.web_import_tab_paste)}</button>
        <button class="btn import-tab-btn" onclick="switchImportTab('file')">${context.getString(R.string.web_import_tab_file)}</button>
        <button class="btn import-tab-btn" onclick="switchImportTab('url')">${context.getString(R.string.web_import_tab_url)}</button>
      </div>
      <div id="import-tab-paste" class="import-tab active">
        <textarea id="importJsonInput" placeholder="Paste collections JSON here..." style="width:100%;min-height:120px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.12);border-radius:8px;padding:0.75rem;color:#fff;font-family:monospace;font-size:0.8rem;resize:vertical"></textarea>
      </div>
      <div id="import-tab-file" class="import-tab">
        <label style="display:block;text-align:center;padding:2rem;border:2px dashed rgba(255,255,255,0.15);border-radius:12px;cursor:pointer;color:rgba(255,255,255,0.4);font-size:0.85rem;transition:border-color 0.2s" id="fileDropLabel">
          <input type="file" id="importFileInput" accept=".json,application/json" style="display:none" onchange="onFileSelected(this)">
          Tap to select a .json file
          <div id="fileSelectedName" style="color:#fff;font-weight:600;margin-top:0.5rem;display:none"></div>
        </label>
      </div>
      <div id="import-tab-url" class="import-tab">
        <input type="url" id="importUrlInput" placeholder="https://example.com/collections.json" style="width:100%;background:transparent;border:1px solid rgba(255,255,255,0.12);border-radius:100px;padding:0.875rem 1.25rem;color:#fff;font-family:inherit;font-size:0.9rem">
      </div>
      <div id="importError" style="color:rgba(207,102,121,0.9);font-size:0.8rem;margin-top:0.5rem;display:none"></div>
      <div id="importSuccess" style="color:rgba(130,200,130,0.9);font-size:0.8rem;margin-top:0.5rem;display:none"></div>
      <div style="display:flex;gap:0.75rem;margin-top:1rem">
        <button class="btn" onclick="dismissImportModal()" style="flex:1">Cancel</button>
        <button class="btn" onclick="doImport()" style="flex:1" id="importBtn">Import</button>
      </div>
    </div>
  </div>

  <button class="btn btn-save" id="saveBtn" onclick="saveChanges()">${context.getString(R.string.web_btn_save)}</button>
</div>

<div class="status-overlay" id="statusOverlay">
  <div class="status-content" id="statusContent"></div>
</div>

<div class="connection-bar" id="connectionBar">${context.getString(R.string.web_connection_lost)}</div>

<script>
var addons = [];
var originalAddons = [];
var catalogs = [];
var originalCatalogs = [];
var collections = [];
var originalCollections = [];
var disabledCollectionKeys = [];
var originalDisabledCollectionKeys = [];
var availableCatalogs = [];
var pollTimer = null;
var pollStartTime = 0;
var POLL_TIMEOUT = 120000;
var POLL_INTERVAL = 1500;

var i18n = {
  newCollection: '${context.getString(R.string.collections_new).replace("'", "\\'")}',
  backdrop: '${context.getString(R.string.collections_editor_backdrop).replace("'", "\\'")}',
  pinAbove: '${context.getString(R.string.collections_editor_pin_above).replace("'", "\\'")}',
  focusGlow: '${context.getString(R.string.collections_editor_focus_glow).replace("'", "\\'")}',
  viewMode: '${context.getString(R.string.collections_editor_view_mode).replace("'", "\\'")}',
  tabs: '${context.getString(R.string.collections_editor_view_mode_tabs).replace("'", "\\'")}',
  rows: '${context.getString(R.string.collections_editor_view_mode_rows).replace("'", "\\'")}',
  followHome: '${context.getString(R.string.collections_editor_view_mode_follow).replace("'", "\\'")}',
  showAllTab: '${context.getString(R.string.collections_editor_show_all_tab).replace("'", "\\'")}',
  cover: '${context.getString(R.string.collections_editor_cover).replace("'", "\\'")}',
  coverNone: '${context.getString(R.string.collections_editor_cover_none).replace("'", "\\'")}',
  coverEmoji: '${context.getString(R.string.collections_editor_cover_emoji).replace("'", "\\'")}',
  coverImage: '${context.getString(R.string.collections_editor_cover_image_url).replace("'", "\\'")}',
  focusGif: '${context.getString(R.string.collections_editor_focus_gif).replace("'", "\\'")}',
  playGif: '${context.getString(R.string.collections_editor_play_gif).replace("'", "\\'")}',
  heroBackdrop: '${context.getString(R.string.collections_editor_hero_backdrop).replace("'", "\\'")}',
  titleLogo: '${context.getString(R.string.collections_editor_title_logo).replace("'", "\\'")}',
  tileShape: '${context.getString(R.string.collections_editor_tile_shape).replace("'", "\\'")}',
  hideTitle: '${context.getString(R.string.collections_editor_hide_title).replace("'", "\\'")}',
  catalogs: '${context.getString(R.string.collections_editor_catalogs).replace("'", "\\'")}',
  addCatalog: '${context.getString(R.string.collections_editor_add_catalog).replace("'", "\\'")}',
  addFolder: '${context.getString(R.string.collections_editor_add_folder).replace("'", "\\'")}',
  folders: '${context.getString(R.string.collections_editor_folders).replace("'", "\\'")}',
  hidden: '${context.getString(R.string.web_badge_disabled).replace("'", "\\'")}',
  display: '${context.getString(R.string.collections_editor_display).replace("'", "\\'")}',
  shape: '${context.getString(R.string.collections_editor_tile_shape).replace("'", "\\'")}',
  shapePoster: '${context.getString(R.string.collections_editor_shape_poster).replace("'", "\\'")}',
  shapeWide: '${context.getString(R.string.collections_editor_shape_wide).replace("'", "\\'")}',
  shapeSquare: '${context.getString(R.string.collections_editor_shape_square).replace("'", "\\'")}',
  tapToPickEmoji: '${context.getString(R.string.collections_editor_cover_emoji).replace("'", "\\'")}',
  added: '${context.getString(R.string.web_btn_add).replace("'", "\\'")}',
  add: '+ ${context.getString(R.string.web_btn_add).replace("'", "\\'")}'
};
var connectionLost = false;
var consecutiveErrors = 0;
var allowAddonManagement = ${allowAddonManagement.toString().lowercase()};
var allowCatalogManagement = ${allowCatalogManagement.toString().lowercase()};
var availableTabs = ${if (isCollectionsOnly) "['collections']" else "['addons','catalogs','collections']"};
var successStatusMessage = '${successStatusMessage.replace("'", "\\'")}';
var activeTab = '$defaultTab';

function switchTab(tab) {
  if (availableTabs.indexOf(tab) < 0) return;
  activeTab = tab;
  document.querySelectorAll('.tab').forEach(function(t, i) {
    t.classList.toggle('active', availableTabs[i] === tab);
  });
  document.querySelectorAll('.tab-content').forEach(function(tc) {
    tc.classList.remove('active');
  });
  var target = document.getElementById('tab-' + tab);
  if (target) target.classList.add('active');
}

function buildUnifiedCatalogList() {
  // Server now sends catalogs with collections already interleaved in saved order.
  // Just mark collection entries with extra flags for the UI.
  catalogs.forEach(function(c) {
    if (c.key && c.key.indexOf('collection_') === 0) {
      c.isCollection = true;
      c.collectionId = c.key.replace('collection_', '');
    }
  });
}

function generateId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    var r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

var EMOJI_CATEGORIES = [
  {name:'Streaming', emojis:['🎬','🎭','🎥','📺','🍿','🎞️','📽️','🎦','📡','📻']},
  {name:'Genres', emojis:['💀','👻','🔪','💣','🚀','🛸','🧙','🦸','🧟','🤖','💘','😂','😱','🤯','🥺','😈']},
  {name:'Sports', emojis:['⚽','🏀','🏈','⚾','🎾','🏐','🏒','🥊','🏎️','🏆','🎯','🏋️']},
  {name:'Music', emojis:['🎵','🎶','🎤','🎸','🥁','🎹','🎷','🎺','🎻','🪗']},
  {name:'Nature', emojis:['🌍','🌊','🏔️','🌋','🌅','🌙','⭐','🔥','❄️','🌈','🌸','🍀']},
  {name:'Animals', emojis:['🐕','🐈','🦁','🐻','🦊','🐺','🦅','🐉','🦋','🐬','🦈','🐙']},
  {name:'Food', emojis:['🍕','🍔','🍣','🍜','🍩','🍰','🍷','🍺','☕','🧁','🌮','🥗']},
  {name:'Travel', emojis:['✈️','🚂','🚗','⛵','🏖️','🗼','🏰','🗽','🎡','🏕️','🌆','🛣️']},
  {name:'People', emojis:['👨‍👩‍👧‍👦','👫','👶','🧒','👩','👨','🧓','💃','🕺','🥷','🧑‍🚀','🧑‍🎨']},
  {name:'Objects', emojis:['📱','💻','🎮','🕹️','📷','🔮','💡','🔑','💎','🎁','📚','✏️']},
  {name:'Flags', emojis:[
    '🏳️‍🌈','🏴‍☠️',
    '🇦🇫','🇦🇱','🇩🇿','🇦🇸','🇦🇩','🇦🇴','🇦🇮','🇦🇬','🇦🇷','🇦🇲','🇦🇼','🇦🇺',
    '🇦🇹','🇦🇿','🇧🇸','🇧🇭','🇧🇩','🇧🇧','🇧🇾','🇧🇪','🇧🇿','🇧🇯','🇧🇲','🇧🇹',
    '🇧🇴','🇧🇦','🇧🇼','🇧🇷','🇧🇳','🇧🇬','🇧🇫','🇧🇮','🇰🇭','🇨🇲','🇨🇦','🇨🇻',
    '🇨🇫','🇹🇩','🇨🇱','🇨🇳','🇨🇴','🇰🇲','🇨🇬','🇨🇩','🇨🇷','🇨🇮','🇭🇷','🇨🇺',
    '🇨🇼','🇨🇾','🇨🇿','🇩🇰','🇩🇯','🇩🇲','🇩🇴','🇪🇨','🇪🇬','🇸🇻','🇬🇶','🇪🇷',
    '🇪🇪','🇸🇿','🇪🇹','🇫🇯','🇫🇮','🇫🇷','🇬🇦','🇬🇲','🇬🇪','🇩🇪','🇬🇭','🇬🇷',
    '🇬🇩','🇬🇹','🇬🇳','🇬🇼','🇬🇾','🇭🇹','🇭🇳','🇭🇰','🇭🇺','🇮🇸','🇮🇳','🇮🇩',
    '🇮🇷','🇮🇶','🇮🇪','🇮🇱','🇮🇹','🇯🇲','🇯🇵','🇯🇴','🇰🇿','🇰🇪','🇰🇮','🇰🇼',
    '🇰🇬','🇱🇦','🇱🇻','🇱🇧','🇱🇸','🇱🇷','🇱🇾','🇱🇮','🇱🇹','🇱🇺','🇲🇴','🇲🇬',
    '🇲🇼','🇲🇾','🇲🇻','🇲🇱','🇲🇹','🇲🇷','🇲🇺','🇲🇽','🇫🇲','🇲🇩','🇲🇨','🇲🇳',
    '🇲🇪','🇲🇦','🇲🇿','🇲🇲','🇳🇦','🇳🇷','🇳🇵','🇳🇱','🇳🇿','🇳🇮','🇳🇪','🇳🇬',
    '🇰🇵','🇲🇰','🇳🇴','🇴🇲','🇵🇰','🇵🇼','🇵🇸','🇵🇦','🇵🇬','🇵🇾','🇵🇪','🇵🇭',
    '🇵🇱','🇵🇹','🇵🇷','🇶🇦','🇷🇴','🇷🇺','🇷🇼','🇰🇳','🇱🇨','🇻🇨','🇼🇸','🇸🇲',
    '🇸🇹','🇸🇦','🇸🇳','🇷🇸','🇸🇨','🇸🇱','🇸🇬','🇸🇰','🇸🇮','🇸🇧','🇸🇴','🇿🇦',
    '🇰🇷','🇸🇸','🇪🇸','🇱🇰','🇸🇩','🇸🇷','🇸🇪','🇨🇭','🇸🇾','🇹🇼','🇹🇯','🇹🇿',
    '🇹🇭','🇹🇱','🇹🇬','🇹🇴','🇹🇹','🇹🇳','🇹🇷','🇹🇲','🇹🇻','🇺🇬','🇺🇦','🇦🇪',
    '🇬🇧','🇺🇸','🇺🇾','🇺🇿','🇻🇺','🇻🇪','🇻🇳','🇾🇪','🇿🇲','🇿🇼'
  ]},
  {name:'Symbols', emojis:['❤️','💜','💙','💚','💛','🧡','🖤','🤍','✅','❌','⚡','💯']}
];

var openEmojiPicker = null;
var expandedCollection = null; // ci index of expanded collection, null = all collapsed
var expandedFolder = null; // 'ci-fi' key of expanded folder, null = all collapsed

function toggleCollectionExpand(ci) {
  expandedCollection = (expandedCollection === ci) ? null : ci;
  expandedFolder = null; // collapse any open folder when switching collection
  renderCollections();
}

function toggleFolderExpand(ci, fi) {
  var key = ci + '-' + fi;
  expandedFolder = (expandedFolder === key) ? null : key;
  renderCollections();
}

function toggleEmojiPicker(ci, fi) {
  var id = 'emoji-grid-' + ci + '-' + fi;
  var el = document.getElementById(id);
  if (!el) return;
  if (openEmojiPicker && openEmojiPicker !== id) {
    var prev = document.getElementById(openEmojiPicker);
    if (prev) prev.classList.remove('open');
  }
  el.classList.toggle('open');
  openEmojiPicker = el.classList.contains('open') ? id : null;
}

function selectEmoji(ci, fi, catIdx, emojiIdx) {
  var emoji = EMOJI_CATEGORIES[catIdx].emojis[emojiIdx];
  collections[ci].folders[fi].coverEmoji = emoji;
  var el = document.getElementById('emoji-grid-' + ci + '-' + fi);
  if (el) el.classList.remove('open');
  openEmojiPicker = null;
  renderCollections();
}

function clearEmoji(ci, fi) {
  collections[ci].folders[fi].coverEmoji = null;
  renderCollections();
}

function filterEmoji(ci, fi, query) {
  var container = document.getElementById('emoji-cells-' + ci + '-' + fi);
  if (!container) return;
  var q = query.toLowerCase();
  var sections = container.querySelectorAll('[data-cat]');
  sections.forEach(function(sec) {
    var catName = sec.getAttribute('data-cat').toLowerCase();
    var cells = sec.querySelectorAll('.emoji-cell');
    var anyVisible = false;
    cells.forEach(function(cell) {
      var show = !q || catName.indexOf(q) >= 0;
      cell.style.display = show ? '' : 'none';
      if (show) anyVisible = true;
    });
    var label = sec.querySelector('.emoji-cat-label');
    if (label) label.style.display = anyVisible ? '' : 'none';
  });
}

function filterCatalogSources(ci, fi, query) {
  var container = document.getElementById('src-list-' + ci + '-' + fi);
  if (!container) return;
  var q = query.toLowerCase();
  var items = container.children;
  for (var i = 0; i < items.length; i++) {
    var text = items[i].getAttribute('data-label') || '';
    items[i].style.display = (!q || text.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
  }
}

function filterActiveSources(ci, fi, query) {
  var container = document.getElementById('active-src-list-' + ci + '-' + fi);
  if (!container) return;
  var q = query.toLowerCase();
  if (q) { container.classList.add('sources-filtering'); }
  else { container.classList.remove('sources-filtering'); }
  var items = container.querySelectorAll('.source-item');
  for (var i = 0; i < items.length; i++) {
    var text = items[i].textContent || '';
    items[i].style.display = (!q || text.toLowerCase().indexOf(q) >= 0) ? '' : 'none';
  }
}

async function fetchWithTimeout(url, options, timeoutMs) {
  var controller = new AbortController();
  var timer = setTimeout(function() { controller.abort(); }, timeoutMs);
  try {
    var opts = options || {};
    opts.signal = controller.signal;
    return await fetch(url, opts);
  } finally {
    clearTimeout(timer);
  }
}

async function loadState() {
  try {
    var res = await fetchWithTimeout('/api/state', {}, 5000);
    var state = await res.json();
    addons = state.addons || [];
    catalogs = state.catalogs || [];
    collections = state.collections || [];
    disabledCollectionKeys = (state.disabledCollectionKeys || []).slice();
    originalAddons = JSON.parse(JSON.stringify(addons));
    originalCatalogs = JSON.parse(JSON.stringify(catalogs));
    originalCollections = JSON.parse(JSON.stringify(collections));
    originalDisabledCollectionKeys = disabledCollectionKeys.slice();
    availableCatalogs = catalogs.map(function(c) {
      return { key: c.key, addonName: c.addonName, catalogName: c.catalogName, type: c.type,
        addonId: c.key.split('_')[0] || '', catalogId: c.key.split('_').slice(2).join('_') || '' };
    });
    buildUnifiedCatalogList();
    setConnectionLost(false);
    renderAddons();
    renderCatalogs();
    renderCollections();
  } catch (e) {
    setConnectionLost(true);
  }
}

function setConnectionLost(lost) {
  connectionLost = lost;
  document.getElementById('connectionBar').className = 'connection-bar' + (lost ? ' visible' : '');
}

function renderAddons() {
  if (!allowAddonManagement) return;
  var list = document.getElementById('addonList');
  var empty = document.getElementById('emptyState');
  if (!list || !empty) return;
  list.innerHTML = '';
  if (addons.length === 0) {
    empty.style.display = 'block';
    return;
  }
  empty.style.display = 'none';
  addons.forEach(function(addon, i) {
    var li = document.createElement('li');
    li.className = 'addon-item';

    var isFirst = (i === 0);
    var isLast = (i === addons.length - 1);

    li.innerHTML =
      '<div class="addon-order">' +
        '<button class="btn-order" onclick="moveAddon(' + i + ',-1)"' + (isFirst ? ' disabled' : '') + '>' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 15l-6-6-6 6"/></svg>' +
        '</button>' +
        '<button class="btn-order" onclick="moveAddon(' + i + ',1)"' + (isLast ? ' disabled' : '') + '>' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>' +
        '</button>' +
      '</div>' +
      '<div class="addon-info">' +
        '<div class="addon-name">' + escapeHtml(addon.name || addon.url) +
          (addon.isNew ? '<span class="badge-new">${context.getString(R.string.web_badge_new).replace("'", "\\'")}</span>' : '') +
        '</div>' +
        (addon.description ? '<div class="addon-desc">' + escapeHtml(addon.description) + '</div>' : '') +
        '<div class="addon-url">' + escapeHtml(addon.url) + '</div>' +
      '</div>' +
      '<div class="addon-actions">' +
        '<button class="btn btn-remove" onclick="removeAddon(' + i + ')">${context.getString(R.string.web_btn_remove).replace("'", "\\'")}</button>' +
      '</div>';

    list.appendChild(li);
  });
}

function renderCatalogs() {
  if (!allowCatalogManagement) return;
  var list = document.getElementById('catalogList');
  var empty = document.getElementById('catalogEmptyState');
  if (!list || !empty) return;
  list.innerHTML = '';

  if (catalogs.length === 0) {
    empty.style.display = 'block';
    return;
  }

  empty.style.display = 'none';
  catalogs.forEach(function(catalog, i) {
    var li = document.createElement('li');
    li.className = 'addon-item';
    if (catalog.isDisabled) li.style.opacity = '0.45';

    var isFirst = (i === 0);
    var isLast = (i === catalogs.length - 1);
    var toggleClass = catalog.isDisabled ? 'btn btn-toggle disabled' : 'btn btn-toggle';
    var isCollection = catalog.isCollection || false;
    var typeDisplay = isCollection ? 'Collection' : formatCatalogTitle(catalog.catalogName, catalog.type);

    li.innerHTML =
      '<div class="addon-order">' +
        '<button class="btn-order" onclick="moveCatalogToTop(' + i + ')"' + (isFirst ? ' disabled' : '') + ' title="Send to top">' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 11l-6-6-6 6"/><path d="M18 18l-6-6-6 6"/></svg>' +
        '</button>' +
        '<button class="btn-order" onclick="moveCatalog(' + i + ',-1)"' + (isFirst ? ' disabled' : '') + ' title="Move up">' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 15l-6-6-6 6"/></svg>' +
        '</button>' +
        '<button class="btn-order" onclick="moveCatalog(' + i + ',1)"' + (isLast ? ' disabled' : '') + ' title="Move down">' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>' +
        '</button>' +
        '<button class="btn-order" onclick="moveCatalogToBottom(' + i + ')"' + (isLast ? ' disabled' : '') + ' title="Send to bottom">' +
          '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l6 6 6-6"/><path d="M6 13l6 6 6-6"/></svg>' +
        '</button>' +
      '</div>' +
      '<div class="catalog-info">' +
        '<div class="catalog-name">' + escapeHtml(formatCatalogTitle(catalog.catalogName, catalog.type)) +
          (isCollection ? '<span class="badge-collection">${context.getString(R.string.web_badge_collection).replace("'", "\\'")}</span>' : '') +
          (catalog.isDisabled ? '<span class="badge-disabled">${context.getString(R.string.web_badge_disabled).replace("'", "\\'")}</span>' : '') +
        '</div>' +
        '<div class="catalog-meta">' + escapeHtml(catalog.addonName) + '</div>' +
      '</div>' +
      '<div class="addon-actions">' +
        '<button class="' + toggleClass + '" onclick="toggleCatalog(' + i + ')">' +
          (catalog.isDisabled ? '${context.getString(R.string.web_btn_enable).replace("'", "\\'")}' : '${context.getString(R.string.web_btn_disable).replace("'", "\\'")}') +
        '</button>' +
      '</div>';

    list.appendChild(li);
  });
}

function moveAddon(index, direction) {
  if (!allowAddonManagement) return;
  var newIndex = index + direction;
  if (newIndex < 0 || newIndex >= addons.length) return;
  var item = addons.splice(index, 1)[0];
  addons.splice(newIndex, 0, item);
  renderAddons();
}

function moveCatalog(index, direction) {
  if (!allowCatalogManagement) return;
  var newIndex = index + direction;
  if (newIndex < 0 || newIndex >= catalogs.length) return;
  var item = catalogs.splice(index, 1)[0];
  catalogs.splice(newIndex, 0, item);
  renderCatalogs();
}

function moveCatalogToTop(index) {
  if (!allowCatalogManagement) return;
  if (index <= 0) return;
  var item = catalogs.splice(index, 1)[0];
  catalogs.unshift(item);
  renderCatalogs();
}

function moveCatalogToBottom(index) {
  if (!allowCatalogManagement) return;
  if (index >= catalogs.length - 1) return;
  var item = catalogs.splice(index, 1)[0];
  catalogs.push(item);
  renderCatalogs();
}

function toggleCatalog(index) {
  if (!allowCatalogManagement) return;
  var item = catalogs[index];
  if (!item) return;
  item.isDisabled = !item.isDisabled;
  // Sync collection disabled state
  if (item.isCollection) {
    var key = 'collection_' + item.collectionId;
    var idx = disabledCollectionKeys.indexOf(key);
    if (item.isDisabled && idx < 0) disabledCollectionKeys.push(key);
    else if (!item.isDisabled && idx >= 0) disabledCollectionKeys.splice(idx, 1);
  }
  renderCatalogs();
}

function enableAllCatalogs() {
  if (!allowCatalogManagement) return;
  catalogs.forEach(function(item) {
    item.isDisabled = false;
    if (item.isCollection) {
      var key = 'collection_' + item.collectionId;
      var idx = disabledCollectionKeys.indexOf(key);
      if (idx >= 0) disabledCollectionKeys.splice(idx, 1);
    }
  });
  renderCatalogs();
}

function disableAllCatalogs() {
  if (!allowCatalogManagement) return;
  catalogs.forEach(function(item) {
    item.isDisabled = true;
    if (item.isCollection) {
      var key = 'collection_' + item.collectionId;
      if (disabledCollectionKeys.indexOf(key) < 0) disabledCollectionKeys.push(key);
    }
  });
  renderCatalogs();
}

function enableAllCollections() {
  disabledCollectionKeys = [];
  catalogs.forEach(function(item) {
    if (item.isCollection) item.isDisabled = false;
  });
  renderCatalogs();
  renderCollections();
}

function disableAllCollections() {
  collections.forEach(function(col) {
    var key = 'collection_' + col.id;
    if (disabledCollectionKeys.indexOf(key) < 0) disabledCollectionKeys.push(key);
  });
  catalogs.forEach(function(item) {
    if (item.isCollection) item.isDisabled = true;
  });
  renderCatalogs();
  renderCollections();
}

async function addAddon() {
  if (!allowAddonManagement) return;
  const input = document.getElementById('addonUrl');
  const errorEl = document.getElementById('addError');
  let url = input.value.trim();
  if (!url) return;

  if (url.startsWith('stremio://')) {
    url = url.replace(/^stremio:\/\//, 'https://');
  }
  if (!url.startsWith('http://') && !url.startsWith('https://')) {
    url = 'https://' + url;
  }
  if (url.endsWith('/manifest.json')) {
    url = url.replace(/\/manifest\.json$/, '');
  }
  url = url.replace(/\/+$/, '');

  if (addons.some(function(a) { return a.url === url; })) {
    errorEl.textContent = '${context.getString(R.string.web_error_addon_exists).replace("'", "\\'")}';
    errorEl.style.display = 'block';
    setTimeout(function() { errorEl.style.display = 'none'; }, 3000);
    return;
  }

  errorEl.style.display = 'none';
  addons.push({ url: url, name: url, description: null, isNew: true });
  input.value = '';
  renderAddons();
}

function removeAddon(index) {
  if (!allowAddonManagement) return;
  addons.splice(index, 1);
  renderAddons();
}

async function saveChanges() {
  var saveBtn = document.getElementById('saveBtn');
  saveBtn.disabled = true;

  var urls = addons.map(function(a) { return a.url; });
  var catalogOrderKeys = catalogs.map(function(c) { return c.key; });
  var disabledCatalogKeys = catalogs
    .filter(function(c) { return c.isDisabled; })
    .map(function(c) { return c.disableKey; });
  try {
    var res = await fetchWithTimeout('/api/addons', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({
        urls: urls,
        catalogOrderKeys: catalogOrderKeys,
        disabledCatalogKeys: disabledCatalogKeys,
        collections: collections,
        disabledCollectionKeys: disabledCollectionKeys
      })
    }, 8000);
    var data = await res.json();

    if (data.status === 'pending_confirmation') {
      showPendingStatus();
      pollStatus(data.id);
    } else if (data.error) {
      showErrorStatus(data.error);
      saveBtn.disabled = false;
    }
  } catch (e) {
    showErrorStatus('${context.getString(R.string.web_error_failed_save).replace("'", "\\'")}');
    saveBtn.disabled = false;
  }
}

function showPendingStatus() {
  var overlay = document.getElementById('statusOverlay');
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="spinner"></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_waiting_tv).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_waiting_tv).replace("'", "\\'")}</div>';
  content.className = 'status-content';
  overlay.classList.add('visible');
}

function showSuccessStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_changes_applied).replace("'", "\\'")}</div>' +
    '<div class="status-message">' + escapeHtml(successStatusMessage) + '</div>';
  content.className = 'status-content status-success';
  setTimeout(dismissStatus, 2500);
}

function showRejectedStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_changes_rejected).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_changes_rejected).replace("'", "\\'")}</div>';
  content.className = 'status-content status-rejected';
  setTimeout(function() {
    addons = JSON.parse(JSON.stringify(originalAddons));
    catalogs = JSON.parse(JSON.stringify(originalCatalogs));
    collections = JSON.parse(JSON.stringify(originalCollections));
    disabledCollectionKeys = originalDisabledCollectionKeys.slice();
    renderAddons();
    renderCatalogs();
    renderCollections();
    dismissStatus();
  }, 2500);
}

function showErrorStatus(msg) {
  var overlay = document.getElementById('statusOverlay');
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_error).replace("'", "\\'")}</div>' +
    '<div class="status-message">' + escapeHtml(msg) + '</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">${context.getString(R.string.web_btn_dismiss).replace("'", "\\'")}</button></div>';
  content.className = 'status-content status-error';
  overlay.classList.add('visible');
}

function showTimeoutStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_status_timeout).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_timeout).replace("'", "\\'")}</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">${context.getString(R.string.web_btn_dismiss).replace("'", "\\'")}</button></div>';
  content.className = 'status-content status-error';
}

function showDisconnectedStatus() {
  var content = document.getElementById('statusContent');
  content.innerHTML =
    '<div class="status-icon"><div class="status-svg"><svg viewBox="0 0 24 24" fill="none" stroke="rgba(207,102,121,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 1l22 22M16.72 11.06A10.94 10.94 0 0 1 19 12.55M5 12.55a10.94 10.94 0 0 1 5.17-2.39M10.71 5.05A16 16 0 0 1 22.56 9M1.42 9a15.91 15.91 0 0 1 4.7-2.88M8.53 16.11a6 6 0 0 1 6.95 0M12 20h.01"/></svg></div></div>' +
    '<div class="status-title">${context.getString(R.string.web_connection_lost).replace("'", "\\'")}</div>' +
    '<div class="status-message">${context.getString(R.string.web_status_msg_connection_lost).replace("'", "\\'")}</div>' +
    '<div class="status-dismiss"><button class="btn" onclick="dismissStatus()">${context.getString(R.string.web_btn_dismiss).replace("'", "\\'")}</button></div>';
  content.className = 'status-content status-error';
}

function dismissStatus() {
  var overlay = document.getElementById('statusOverlay');
  overlay.classList.remove('visible');
  document.getElementById('saveBtn').disabled = false;
  if (pollTimer) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function pollStatus(changeId) {
  pollStartTime = Date.now();
  consecutiveErrors = 0;

  var poll = async function() {
    if (Date.now() - pollStartTime > POLL_TIMEOUT) {
      showTimeoutStatus();
      document.getElementById('saveBtn').disabled = false;
      return;
    }

    try {
      var res = await fetchWithTimeout('/api/status/' + changeId, {}, 4000);
      var data = await res.json();
      consecutiveErrors = 0;

      if (data.status === 'confirmed') {
        showSuccessStatus();
        setTimeout(function() {
          loadState();
          document.getElementById('saveBtn').disabled = false;
        }, 2000);
      } else if (data.status === 'rejected') {
        showRejectedStatus();
      } else if (data.status === 'not_found') {
        showDisconnectedStatus();
        document.getElementById('saveBtn').disabled = false;
      } else {
        pollTimer = setTimeout(poll, POLL_INTERVAL);
      }
    } catch (e) {
      consecutiveErrors++;
      if (consecutiveErrors >= 3) {
        showDisconnectedStatus();
        document.getElementById('saveBtn').disabled = false;
      } else {
        pollTimer = setTimeout(poll, 2000);
      }
    }
  };
  poll();
}

function escapeHtml(str) {
  var div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

function formatCatalogTitle(name, type) {
  var safeName = name || '';
  var safeType = type || '';
  if (!safeType) return safeName;
  return safeName + ' - ' + toTitleCase(safeType);
}

function toTitleCase(value) {
  if (!value) return '';
  return value.charAt(0).toUpperCase() + value.slice(1);
}

function toggleCollection(ci) {
  var key = 'collection_' + collections[ci].id;
  var idx = disabledCollectionKeys.indexOf(key);
  if (idx >= 0) { disabledCollectionKeys.splice(idx, 1); }
  else { disabledCollectionKeys.push(key); }
  renderCollections();
}

function isCollectionDisabled(ci) {
  return disabledCollectionKeys.indexOf('collection_' + collections[ci].id) >= 0;
}

function addCollection() {
  collections.push({ id: generateId(), title: i18n.newCollection, backdropImageUrl: null, pinToTop: false, focusGlowEnabled: true, viewMode: 'TABBED_GRID', showAllTab: true, folders: [] });
  expandedCollection = collections.length - 1;
  expandedFolder = null;
  renderCollections();
}

function updateCollectionBackdrop(ci, val) {
  collections[ci].backdropImageUrl = val || null;
  var img = document.getElementById('col-backdrop-preview-' + ci);
  if (val) {
    if (img) { img.src = val; img.style.display = ''; }
    else { renderCollections(); }
  } else {
    if (img) img.style.display = 'none';
  }
}

function removeCollection(ci) {
  collections.splice(ci, 1);
  renderCollections();
}

function moveCollection(ci, dir) {
  var ni = ci + dir;
  if (ni < 0 || ni >= collections.length) return;
  var item = collections.splice(ci, 1)[0];
  collections.splice(ni, 0, item);
  renderCollections();
}

function updateCollectionTitle(ci, val) {
  collections[ci].title = val;
}

function addFolder(ci) {
  collections[ci].folders.push({ id: generateId(), title: 'New Folder', coverImageUrl: null, focusGifUrl: null, focusGifEnabled: true, coverEmoji: null, tileShape: 'SQUARE', hideTitle: false, heroBackdropUrl: null, titleLogoUrl: null, catalogSources: [] });
  expandedFolder = ci + '-' + (collections[ci].folders.length - 1);
  renderCollections();
}

function removeFolder(ci, fi) {
  collections[ci].folders.splice(fi, 1);
  renderCollections();
}

function moveFolder(ci, fi, dir) {
  var folders = collections[ci].folders;
  var ni = fi + dir;
  if (ni < 0 || ni >= folders.length) return;
  var item = folders.splice(fi, 1)[0];
  folders.splice(ni, 0, item);
  renderCollections();
}

function updateFolderTitle(ci, fi, val) {
  collections[ci].folders[fi].title = val;
}

function updateFolderCoverImage(ci, fi, val) {
  collections[ci].folders[fi].coverImageUrl = val || null;
  var img = document.getElementById('cover-preview-' + ci + '-' + fi);
  if (val) {
    if (img) { img.src = val; img.style.display = ''; }
    else {
      // Preview element doesn't exist yet, need re-render to add it
      renderCollections();
    }
  } else {
    if (img) img.style.display = 'none';
  }
}

function updateFolderFocusGifUrl(ci, fi, val) {
  collections[ci].folders[fi].focusGifUrl = val || null;
}

function updateFolderFocusGifEnabled(ci, fi, checked) {
  collections[ci].folders[fi].focusGifEnabled = checked;
}

function updateFolderHeroBackdropUrl(ci, fi, val) {
  collections[ci].folders[fi].heroBackdropUrl = val || null;
  var img = document.getElementById('hero-backdrop-preview-' + ci + '-' + fi);
  if (val) {
    if (img) { img.src = val; img.style.display = ''; }
    else { renderCollections(); }
  } else {
    if (img) img.style.display = 'none';
  }
}

function updateFolderTitleLogoUrl(ci, fi, val) {
  collections[ci].folders[fi].titleLogoUrl = val || null;
  var img = document.getElementById('title-logo-preview-' + ci + '-' + fi);
  if (val) {
    if (img) { img.src = val; img.style.display = ''; }
    else { renderCollections(); }
  } else {
    if (img) img.style.display = 'none';
  }
}

function updateFolderCoverEmoji(ci, fi, val) {
  collections[ci].folders[fi].coverEmoji = val || null;
}

function updateFolderTileShape(ci, fi, val) {
  collections[ci].folders[fi].tileShape = val;
}

function setFolderCoverMode(ci, fi, mode) {
  var folder = collections[ci].folders[fi];
  if (!folder._coverMode) folder._coverMode = folder.coverEmoji ? 'emoji' : (folder.coverImageUrl ? 'image' : 'none');
  folder._coverMode = mode;
  if (mode === 'none') {
    folder.coverEmoji = null;
    folder.coverImageUrl = null;
  } else if (mode === 'emoji') {
    folder.coverImageUrl = null;
  } else if (mode === 'image') {
    folder.coverEmoji = null;
  }
  renderCollections();
}

function updateCollectionViewMode(ci, val) {
  collections[ci].viewMode = val;
  renderCollections();
}

function updateCollectionShowAllTab(ci, checked) {
  collections[ci].showAllTab = checked;
}

function updateCollectionPinToTop(ci, checked) {
  collections[ci].pinToTop = checked;
}

function updateCollectionFocusGlow(ci, checked) {
  collections[ci].focusGlowEnabled = checked;
}

function updateFolderHideTitle(ci, fi, checked) {
  collections[ci].folders[fi].hideTitle = checked;
}

function addCatalogSource(ci, fi) {
  var sel = document.getElementById('src-sel-' + ci + '-' + fi);
  if (!sel || !sel.value) return;
  addCatalogSourceByVal(ci, fi, sel.value);
}

function addCatalogSourceByVal(ci, fi, val) {
  var parts = val.split('::');
  if (parts.length < 3) return;
  var src = { addonId: parts[0], type: parts[1], catalogId: parts[2] };
  var existing = collections[ci].folders[fi].catalogSources;
  var dup = existing.some(function(s) { return s.addonId === src.addonId && s.type === src.type && s.catalogId === src.catalogId; });
  if (dup) return;
  existing.push(src);
  renderCollections();
}

function removeCatalogSource(ci, fi, si) {
  collections[ci].folders[fi].catalogSources.splice(si, 1);
  renderCollections();
}

function moveCatalogSource(ci, fi, si, dir) {
  var sources = collections[ci].folders[fi].catalogSources;
  var ni = si + dir;
  if (ni < 0 || ni >= sources.length) return;
  var item = sources.splice(si, 1)[0];
  sources.splice(ni, 0, item);
  renderCollections();
}

function catalogSourceLabel(src) {
  var match = availableCatalogs.find(function(c) {
    return c.key === src.addonId + '_' + src.type + '_' + src.catalogId;
  });
  if (match) return match.catalogName + ' - ' + toTitleCase(match.type) + ' (' + match.addonName + ')';
  return src.catalogId + ' - ' + toTitleCase(src.type) + ' (' + src.addonId + ')';
}

function getCollectionErrors(col) {
  var errors = [];
  if (!col.title || !col.title.trim()) errors.push('Missing title');
  if (!col.folders || col.folders.length === 0) errors.push('No folders');
  (col.folders || []).forEach(function(f, fi) {
    if (!f.catalogSources || f.catalogSources.length === 0) {
      errors.push((f.title || 'Folder ' + (fi + 1)) + ': no sources');
    }
  });
  return errors;
}

function updateSaveButtonState() {
  var hasIssues = collections.some(function(col) { return getCollectionErrors(col).length > 0; });
  var saveBtn = document.getElementById('saveBtn');
  if (saveBtn && !saveBtn._polling) {
    saveBtn.style.opacity = hasIssues ? '0.35' : '';
    saveBtn.style.pointerEvents = hasIssues ? 'none' : '';
  }
}

function renderCollections() {
  var container = document.getElementById('collectionsList');
  var empty = document.getElementById('collectionsEmptyState');
  container.innerHTML = '';
  if (collections.length === 0) { empty.style.display = 'block'; return; }
  empty.style.display = 'none';

  collections.forEach(function(col, ci) {
    var disabled = isCollectionDisabled(ci);
    var card = document.createElement('div');
    card.className = 'collection-card' + (disabled ? ' collection-disabled' : '');

    var isExpanded = (expandedCollection === ci);
    var folderCount = (col.folders || []).length;
    var errors = getCollectionErrors(col);

    // ── Collection header: arrow + title + action buttons ──
    var headerHtml =
      '<div class="collection-header collapse-header" onclick="toggleCollectionExpand(' + ci + ')">' +
        '<span class="collapse-arrow' + (isExpanded ? ' open' : '') + '">&#9654;</span>' +
        '<input class="collection-title-input" value="' + escapeAttr(col.title) + '" onchange="updateCollectionTitle(' + ci + ',this.value);updateSaveButtonState()" onclick="event.stopPropagation()" placeholder="Collection name">' +
        (disabled ? '<span class="badge-collection-disabled">' + i18n.hidden + '</span>' : '') +
        (errors.length > 0 ? '<span style="font-size:0.6rem;font-weight:700;color:rgba(255,180,60,0.9);background:rgba(255,180,60,0.12);padding:0.2rem 0.5rem;border-radius:100px;flex-shrink:0">' + errors.length + ' issue' + (errors.length > 1 ? 's' : '') + '</span>' : '') +
        '<div class="col-actions" onclick="event.stopPropagation()">' +
          '<button class="btn-order" onclick="moveCollection(' + ci + ',-1)"' + (ci === 0 ? ' disabled' : '') + '>' +
            '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 15l-6-6-6 6"/></svg>' +
          '</button>' +
          '<button class="btn-order" onclick="moveCollection(' + ci + ',1)"' + (ci === collections.length - 1 ? ' disabled' : '') + '>' +
            '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>' +
          '</button>' +
          '<button class="btn-icon" onclick="toggleCollection(' + ci + ')" title="' + (disabled ? 'Show' : 'Hide') + '">' +
            '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="' + (disabled ? 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M12 9a3 3 0 100 6 3 3 0 000-6z' : 'M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19M1 1l22 22') + '"/></svg>' +
          '</button>' +
          '<button class="btn-icon danger" onclick="removeCollection(' + ci + ')" title="Remove">' +
            '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>' +
          '</button>' +
        '</div>' +
      '</div>';

    if (!isExpanded) {
      card.innerHTML = headerHtml +
        '<div class="folder-summary">' + i18n.folders + ': ' + folderCount + '</div>';
      container.appendChild(card);
      return;
    }

    // ── Collection settings (expanded) ──
    var settingsHtml =
      '<div class="col-settings">' +
        '<div class="col-setting-row">' +
          '<span class="col-meta-label">' + i18n.backdrop + '</span>' +
          '<img id="col-backdrop-preview-' + ci + '" src="' + escapeAttr(col.backdropImageUrl || '') + '" style="' + (col.backdropImageUrl ? '' : 'display:none') + '" onerror="this.style.display=\'none\'">' +
          '<input type="url" placeholder="Image URL (optional)" value="' + escapeAttr(col.backdropImageUrl || '') + '" oninput="updateCollectionBackdrop(' + ci + ',this.value)">' +
        '</div>' +
        '<div class="col-setting-row">' +
          '<span class="toggle-label">' + i18n.pinAbove + '</span>' +
          '<label class="toggle-switch" onclick="event.stopPropagation()">' +
            '<input type="checkbox"' + (col.pinToTop ? ' checked' : '') + ' onchange="updateCollectionPinToTop(' + ci + ',this.checked)">' +
            '<span class="toggle-track"></span>' +
            '<span class="toggle-thumb"></span>' +
          '</label>' +
        '</div>' +
        '<div class="col-setting-row">' +
          '<span class="toggle-label">' + i18n.focusGlow + '</span>' +
          '<label class="toggle-switch" onclick="event.stopPropagation()">' +
            '<input type="checkbox"' + (col.focusGlowEnabled !== false ? ' checked' : '') + ' onchange="updateCollectionFocusGlow(' + ci + ',this.checked)">' +
            '<span class="toggle-track"></span>' +
            '<span class="toggle-thumb"></span>' +
          '</label>' +
        '</div>' +
        '<div class="col-setting-row">' +
          '<span class="col-meta-label">' + i18n.viewMode + '</span>' +
          '<div class="cover-mode-picker">' +
            '<button class="cover-mode-btn' + ((col.viewMode === 'TABBED_GRID' || !col.viewMode) ? ' active' : '') + '" onclick="updateCollectionViewMode(' + ci + ',\'TABBED_GRID\')">' + i18n.tabs + '</button>' +
            '<button class="cover-mode-btn' + (col.viewMode === 'ROWS' ? ' active' : '') + '" onclick="updateCollectionViewMode(' + ci + ',\'ROWS\')">' + i18n.rows + '</button>' +
            '<button class="cover-mode-btn' + (col.viewMode === 'FOLLOW_LAYOUT' ? ' active' : '') + '" onclick="updateCollectionViewMode(' + ci + ',\'FOLLOW_LAYOUT\')">' + i18n.followHome + '</button>' +
          '</div>' +
        '</div>' +
        ((col.viewMode === 'TABBED_GRID' || !col.viewMode) ?
        '<div class="col-setting-row">' +
          '<span class="toggle-label">' + i18n.showAllTab + '</span>' +
          '<label class="toggle-switch" onclick="event.stopPropagation()">' +
            '<input type="checkbox"' + (col.showAllTab !== false ? ' checked' : '') + ' onchange="updateCollectionShowAllTab(' + ci + ',this.checked)">' +
            '<span class="toggle-track"></span>' +
            '<span class="toggle-thumb"></span>' +
          '</label>' +
        '</div>' : '') +
      '</div>';

    // ── Folders ──
    var foldersHtml = '';
    (col.folders || []).forEach(function(folder, fi) {
      var sourcesHtml = '';
      (folder.catalogSources || []).forEach(function(src, si) {
        var isFirstSrc = (si === 0);
        var isLastSrc = (si === folder.catalogSources.length - 1);
        sourcesHtml +=
          '<div class="source-item">' +
            '<button class="btn-icon" onclick="moveCatalogSource(' + ci + ',' + fi + ',' + si + ',-1)"' + (isFirstSrc ? ' disabled' : '') + '>' +
              '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 15l-6-6-6 6"/></svg>' +
            '</button>' +
            '<button class="btn-icon" onclick="moveCatalogSource(' + ci + ',' + fi + ',' + si + ',1)"' + (isLastSrc ? ' disabled' : '') + '>' +
              '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>' +
            '</button>' +
            '<span class="source-label">' + escapeHtml(catalogSourceLabel(src)) + '</span>' +
            '<button class="btn-icon danger" onclick="removeCatalogSource(' + ci + ',' + fi + ',' + si + ')">' +
              '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>' +
            '</button>' +
          '</div>';
      });

      var emojiCellsHtml = '';
      EMOJI_CATEGORIES.forEach(function(cat, catIdx) {
        emojiCellsHtml += '<div data-cat="' + cat.name + '">';
        emojiCellsHtml += '<div class="emoji-cat-label" style="grid-column:1/-1;font-size:0.65rem;font-weight:600;color:rgba(255,255,255,0.3);text-transform:uppercase;letter-spacing:0.06em;padding:0.5rem 0 0.25rem">' + escapeHtml(cat.name) + '</div>';
        cat.emojis.forEach(function(em, emIdx) {
          emojiCellsHtml += '<button class="emoji-cell" onclick="selectEmoji(' + ci + ',' + fi + ',' + catIdx + ',' + emIdx + ')">' + em + '</button>';
        });
        emojiCellsHtml += '</div>';
      });

      var existingSources = (folder.catalogSources || []);
      var sourceListHtml = '';
      availableCatalogs.filter(function(c) { return c.type !== 'collection'; }).forEach(function(c) {
        var val = c.key.split('_')[0] + '::' + c.type + '::' + c.key.split('_').slice(2).join('_');
        var parts = val.split('::');
        var alreadyAdded = existingSources.some(function(s) { return s.addonId === parts[0] && s.type === parts[1] && s.catalogId === parts[2]; });
        var label = c.catalogName + ' - ' + toTitleCase(c.type) + ' (' + c.addonName + ')';
        if (alreadyAdded) {
          sourceListHtml += '<div class="source-item" data-label="' + escapeAttr(label) + '" style="padding:0.4rem 0.75rem;opacity:0.4">' +
            '<span class="source-label">' + escapeHtml(label) + '</span>' +
            '<span style="font-size:0.7rem;color:rgba(130,200,130,0.85);flex-shrink:0">Added</span>' +
          '</div>';
        } else {
          sourceListHtml += '<div class="source-item" data-label="' + escapeAttr(label) + '" style="cursor:pointer;padding:0.4rem 0.75rem" onclick="addCatalogSourceByVal(' + ci + ',' + fi + ',\'' + escapeAttr(val) + '\')">' +
            '<span class="source-label" style="color:rgba(255,255,255,0.45)">' + escapeHtml(label) + '</span>' +
            '<span style="font-size:0.7rem;color:rgba(255,255,255,0.2);flex-shrink:0">+ Add</span>' +
          '</div>';
        }
      });

      var isFolderExpanded = (expandedFolder === ci + '-' + fi);
      var srcCount = (folder.catalogSources || []).length;
      var coverMode = folder._coverMode || (folder.coverEmoji ? 'emoji' : (folder.coverImageUrl ? 'image' : 'none'));

      foldersHtml +=
        '<div class="folder-card">' +
          '<div class="folder-header collapse-header" onclick="toggleFolderExpand(' + ci + ',' + fi + ')">' +
            '<span class="collapse-arrow' + (isFolderExpanded ? ' open' : '') + '">&#9654;</span>' +
            '<input class="folder-title-input" value="' + escapeAttr(folder.title) + '" onchange="updateFolderTitle(' + ci + ',' + fi + ',this.value)" onclick="event.stopPropagation()" placeholder="Folder name">' +
            (!isFolderExpanded ? '<span style="font-size:0.7rem;color:rgba(255,255,255,0.3);background:rgba(255,255,255,0.06);padding:0.15rem 0.5rem;border-radius:100px;flex-shrink:0">' + srcCount + ' source' + (srcCount !== 1 ? 's' : '') + '</span>' : '') +
            '<div class="col-actions" onclick="event.stopPropagation()">' +
              '<button class="btn-order" onclick="moveFolder(' + ci + ',' + fi + ',-1)"' + (fi === 0 ? ' disabled' : '') + ' style="width:22px;height:22px">' +
                '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 15l-6-6-6 6"/></svg>' +
              '</button>' +
              '<button class="btn-order" onclick="moveFolder(' + ci + ',' + fi + ',1)"' + (fi === col.folders.length - 1 ? ' disabled' : '') + ' style="width:22px;height:22px">' +
                '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M6 9l6 6 6-6"/></svg>' +
              '</button>' +
              '<button class="btn-icon danger" onclick="removeFolder(' + ci + ',' + fi + ')" style="width:22px;height:22px">' +
                '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>' +
              '</button>' +
            '</div>' +
          '</div>' +
          (isFolderExpanded ?
          '<div class="folder-settings">' +
            '<div class="folder-settings-group">' +
              '<div class="folder-settings-group-label">' + i18n.cover + '</div>' +
              '<div class="folder-setting-item">' +
                '<div class="cover-mode-picker">' +
                  '<button class="cover-mode-btn' + (coverMode === 'none' ? ' active' : '') + '" onclick="setFolderCoverMode(' + ci + ',' + fi + ',\'none\')">' + i18n.coverNone + '</button>' +
                  '<button class="cover-mode-btn' + (coverMode === 'emoji' ? ' active' : '') + '" onclick="setFolderCoverMode(' + ci + ',' + fi + ',\'emoji\')">' + i18n.coverEmoji + '</button>' +
                  '<button class="cover-mode-btn' + (coverMode === 'image' ? ' active' : '') + '" onclick="setFolderCoverMode(' + ci + ',' + fi + ',\'image\')">' + i18n.coverImage + '</button>' +
                '</div>' +
              '</div>' +
              (coverMode === 'emoji' ?
              '<div class="folder-setting-item">' +
                '<button class="emoji-picker-btn" onclick="toggleEmojiPicker(' + ci + ',' + fi + ')">' +
                  (folder.coverEmoji ? escapeHtml(folder.coverEmoji) : '😀') +
                '</button>' +
                '<span style="font-size:0.78rem;color:rgba(255,255,255,0.3);flex:1">' + i18n.tapToPickEmoji + '</span>' +
              '</div>' +
              '<div id="emoji-grid-' + ci + '-' + fi + '" class="emoji-grid-wrap" style="margin:0 0.75rem 0.5rem">' +
                '<input class="emoji-grid-search" placeholder="Search emoji..." oninput="filterEmoji(' + ci + ',' + fi + ',this.value)">' +
                '<div class="emoji-grid" id="emoji-cells-' + ci + '-' + fi + '">' + emojiCellsHtml + '</div>' +
              '</div>' : '') +
              (coverMode === 'image' ?
              '<div class="folder-setting-item">' +
                '<img id="cover-preview-' + ci + '-' + fi + '" src="' + escapeAttr(folder.coverImageUrl || '') + '" style="' + (folder.coverImageUrl ? '' : 'display:none') + '" onerror="this.style.display=\'none\'">' +
                '<input type="url" placeholder="Cover image URL" value="' + escapeAttr(folder.coverImageUrl || '') + '" oninput="updateFolderCoverImage(' + ci + ',' + fi + ',this.value)">' +
              '</div>' : '') +
              '<div class="folder-setting-item">' +
                '<input type="url" placeholder="Focused GIF URL (optional)" value="' + escapeAttr(folder.focusGifUrl || '') + '" oninput="updateFolderFocusGifUrl(' + ci + ',' + fi + ',this.value)">' +
              '</div>' +
              '<div class="folder-setting-item">' +
                '<span class="toggle-label">' + i18n.playGif + '</span>' +
                '<label class="toggle-switch">' +
                  '<input type="checkbox"' + (folder.focusGifEnabled !== false ? ' checked' : '') + ' onchange="updateFolderFocusGifEnabled(' + ci + ',' + fi + ',this.checked)">' +
                  '<span class="toggle-track"></span>' +
                  '<span class="toggle-thumb"></span>' +
                '</label>' +
              '</div>' +
            '</div>' +
            '<div class="folder-settings-group">' +
              '<div class="folder-settings-group-label">' + i18n.display + '</div>' +
              '<div class="folder-setting-item">' +
                '<span class="folder-setting-label">' + i18n.shape + '</span>' +
                '<select onchange="updateFolderTileShape(' + ci + ',' + fi + ',this.value)">' +
                  '<option value="POSTER"' + (folder.tileShape === 'POSTER' ? ' selected' : '') + '>' + i18n.shapePoster + '</option>' +
                  '<option value="LANDSCAPE"' + (folder.tileShape === 'LANDSCAPE' ? ' selected' : '') + '>' + i18n.shapeWide + '</option>' +
                  '<option value="SQUARE"' + ((folder.tileShape === 'SQUARE' || !folder.tileShape) ? ' selected' : '') + '>' + i18n.shapeSquare + '</option>' +
                '</select>' +
              '</div>' +
              '<div class="folder-setting-item">' +
                '<span class="toggle-label">' + i18n.hideTitle + '</span>' +
                '<label class="toggle-switch">' +
                  '<input type="checkbox" id="ht-' + ci + '-' + fi + '"' + (folder.hideTitle ? ' checked' : '') + ' onchange="updateFolderHideTitle(' + ci + ',' + fi + ',this.checked)">' +
                  '<span class="toggle-track"></span>' +
                  '<span class="toggle-thumb"></span>' +
                '</label>' +
              '</div>' +
              (col.viewMode === 'FOLLOW_LAYOUT' ?
              '<div class="folder-setting-item">' +
                '<span class="folder-setting-label">' + i18n.heroBackdrop + '</span>' +
                '<img id="hero-backdrop-preview-' + ci + '-' + fi + '" src="' + escapeAttr(folder.heroBackdropUrl || '') + '" style="' + (folder.heroBackdropUrl ? '' : 'display:none') + '" onerror="this.style.display=\'none\'">' +
                '<input type="url" placeholder="Hero backdrop URL" value="' + escapeAttr(folder.heroBackdropUrl || '') + '" oninput="updateFolderHeroBackdropUrl(' + ci + ',' + fi + ',this.value)">' +
              '</div>' +
              '<div class="folder-setting-item">' +
                '<span class="folder-setting-label">' + i18n.titleLogo + '</span>' +
                '<img id="title-logo-preview-' + ci + '-' + fi + '" src="' + escapeAttr(folder.titleLogoUrl || '') + '" style="' + (folder.titleLogoUrl ? '' : 'display:none;') + 'width:52px;height:32px;object-fit:contain" onerror="this.style.display=\'none\'">' +
                '<input type="url" placeholder="Title logo URL" value="' + escapeAttr(folder.titleLogoUrl || '') + '" oninput="updateFolderTitleLogoUrl(' + ci + ',' + fi + ',this.value)">' +
              '</div>' : '') +
            '</div>' +
            '<div class="folder-settings-group">' +
              '<div class="folder-settings-group-label">' + i18n.catalogs + '</div>' +
              '<div style="padding:0.5rem 0.75rem">' +
                '<input class="source-search-input" placeholder="Filter active sources..." oninput="filterActiveSources(' + ci + ',' + fi + ',this.value)" id="active-src-search-' + ci + '-' + fi + '">' +
                '<div id="active-src-list-' + ci + '-' + fi + '" style="max-height:180px;overflow-y:auto;border:1px solid rgba(255,255,255,0.05);border-radius:8px;margin-top:0.25rem">' +
                sourcesHtml +
                (sourcesHtml ? '' : '<div style="padding:0.4rem 0.5rem;font-size:0.78rem;color:rgba(255,255,255,0.2)">No sources added yet</div>') +
                '</div>' +
              '</div>' +
            '</div>' +
            '<div class="folder-settings-group" style="margin-top:0.5rem">' +
              '<div class="folder-settings-group-label">' + i18n.addCatalog + '</div>' +
              '<div style="padding:0.5rem 0.75rem">' +
                '<input class="source-search-input" placeholder="Search catalogs..." oninput="filterCatalogSources(' + ci + ',' + fi + ',this.value)" id="src-search-' + ci + '-' + fi + '">' +
                '<div id="src-list-' + ci + '-' + fi + '" style="max-height:200px;overflow-y:auto;border:1px solid rgba(255,255,255,0.05);border-radius:8px;margin-top:0.25rem">' + sourceListHtml + '</div>' +
              '</div>' +
            '</div>' +
          '</div>'
          : '') +
        '</div>';
    });

    card.innerHTML = headerHtml + settingsHtml + foldersHtml +
      '<div style="padding:0.5rem 1rem 0.875rem"><button class="btn" onclick="addFolder(' + ci + ')" style="width:100%;padding:0.6rem;font-size:0.8rem">+ ' + i18n.addFolder + '</button></div>';

    container.appendChild(card);
  });
  updateSaveButtonState();
}

function escapeAttr(str) {
  if (!str) return '';
  return str.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function exportCollections() {
  if (collections.length === 0) return;
  var json = JSON.stringify(collections, null, 2);
  var blob = new Blob([json], { type: 'application/json' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = 'nuvio-collections.json';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

var activeImportTab = 'paste';

function showImportModal() {
  document.getElementById('importOverlay').classList.add('visible');
  document.getElementById('importJsonInput').value = '';
  document.getElementById('importUrlInput').value = '';
  document.getElementById('importFileInput').value = '';
  document.getElementById('fileSelectedName').style.display = 'none';
  importFileContent = null;
  document.getElementById('importError').style.display = 'none';
  document.getElementById('importSuccess').style.display = 'none';
}

function dismissImportModal() {
  document.getElementById('importOverlay').classList.remove('visible');
}

var importFileContent = null;

function switchImportTab(tab) {
  activeImportTab = tab;
  document.querySelectorAll('.import-tab-btn').forEach(function(b, i) {
    b.classList.toggle('active', ['paste','file','url'][i] === tab);
  });
  document.querySelectorAll('.import-tab').forEach(function(t) { t.classList.remove('active'); });
  document.getElementById('import-tab-' + tab).classList.add('active');
}

function onFileSelected(input) {
  var file = input.files[0];
  if (!file) return;
  document.getElementById('fileSelectedName').textContent = file.name;
  document.getElementById('fileSelectedName').style.display = 'block';
  var reader = new FileReader();
  reader.onload = function(e) { importFileContent = e.target.result; };
  reader.readAsText(file);
}

async function doImport() {
  var errEl = document.getElementById('importError');
  var sucEl = document.getElementById('importSuccess');
  errEl.style.display = 'none';
  sucEl.style.display = 'none';

  var json = '';
  if (activeImportTab === 'paste') {
    json = document.getElementById('importJsonInput').value.trim();
  } else if (activeImportTab === 'file') {
    if (!importFileContent) { errEl.textContent = 'Select a file first'; errEl.style.display = 'block'; return; }
    json = importFileContent.trim();
  } else {
    var url = document.getElementById('importUrlInput').value.trim();
    if (!url) { errEl.textContent = 'Enter a URL'; errEl.style.display = 'block'; return; }
    try {
      var res = await fetch(url);
      json = await res.text();
    } catch (e) {
      errEl.textContent = 'Failed to fetch URL: ' + e.message;
      errEl.style.display = 'block';
      return;
    }
  }

  if (!json) { errEl.textContent = 'No JSON provided'; errEl.style.display = 'block'; return; }

  try {
    var parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) { errEl.textContent = 'Expected a JSON array of collections'; errEl.style.display = 'block'; return; }
    if (parsed.length === 0) { errEl.textContent = 'Empty array: no collections found'; errEl.style.display = 'block'; return; }
    var validShapes = ['POSTER','LANDSCAPE','SQUARE','poster','wide','square'];
    for (var i = 0; i < parsed.length; i++) {
      var c = parsed[i];
      if (!c.id || typeof c.id !== 'string') { errEl.textContent = 'Collection ' + (i+1) + ': missing or invalid "id"'; errEl.style.display = 'block'; return; }
      if (!c.title || typeof c.title !== 'string') { errEl.textContent = 'Collection "' + (c.id) + '": missing or invalid "title"'; errEl.style.display = 'block'; return; }
      if (!Array.isArray(c.folders)) { errEl.textContent = 'Collection "' + c.title + '": "folders" must be an array'; errEl.style.display = 'block'; return; }
      for (var j = 0; j < c.folders.length; j++) {
        var f = c.folders[j];
        if (!f || typeof f !== 'object') { errEl.textContent = 'Collection "' + c.title + '", folder ' + (j+1) + ': invalid format'; errEl.style.display = 'block'; return; }
        if (!f.id || typeof f.id !== 'string') { errEl.textContent = 'Collection "' + c.title + '", folder ' + (j+1) + ': missing "id"'; errEl.style.display = 'block'; return; }
        if (!f.title || typeof f.title !== 'string') { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.id + '": missing "title"'; errEl.style.display = 'block'; return; }
        if (!Array.isArray(f.catalogSources)) { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '": "catalogSources" must be an array'; errEl.style.display = 'block'; return; }
        if (f.tileShape && validShapes.indexOf(f.tileShape) < 0) { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '": invalid tileShape "' + f.tileShape + '"'; errEl.style.display = 'block'; return; }
        for (var k = 0; k < f.catalogSources.length; k++) {
          var s = f.catalogSources[k];
          if (!s || typeof s !== 'object') { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '", source ' + (k+1) + ': invalid format'; errEl.style.display = 'block'; return; }
          if (typeof s.addonId !== 'string' || typeof s.type !== 'string' || typeof s.catalogId !== 'string') { errEl.textContent = 'Collection "' + c.title + '", folder "' + f.title + '", source ' + (k+1) + ': missing required fields (addonId, type, catalogId)'; errEl.style.display = 'block'; return; }
        }
      }
    }
    // Merge: replace existing by id, append new
    var existingById = {};
    collections.forEach(function(c, idx) { existingById[c.id] = idx; });
    parsed.forEach(function(imported) {
      if (imported.id in existingById) {
        collections[existingById[imported.id]] = imported;
      } else {
        collections.push(imported);
      }
    });
    renderCollections();
    sucEl.textContent = 'Imported ' + parsed.length + ' collection(s). Review and hit Save Changes to apply.';
    sucEl.style.display = 'block';
    setTimeout(function() { dismissImportModal(); }, 2000);
  } catch (e) {
    errEl.textContent = 'Invalid JSON: ' + e.message;
    errEl.style.display = 'block';
  }
}

document.getElementById('addonUrl').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') addAddon();
});

loadState();
</script>
</body>
</html>
""".trimIndent()
    }
}
