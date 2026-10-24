// Adapted from https://github.com/autovalue/nuvio-test-plugins/blob/main/providers/test-settings.js

function getSettings(key, defaultValue) {
  const settings = globalThis.SCRAPER_SETTINGS || {};
  if (Object.prototype.hasOwnProperty.call(settings, key)) {
    return settings[key];
  }
  return defaultValue;
}

function getStreams() {
  const previousScrape = localStorage.getItem('previousScrape');
  const updateLastScrape = getSettings('update-last-scrape', true);

  if (updateLastScrape) {
    localStorage.setItem('previousScrape', 'updated-by-scrape');
  }

  return [{
    name: 'settings-test',
    title: JSON.stringify({
      updateLastScrape: updateLastScrape,
      text: getSettings('test-text', 'default-text'),
      select: getSettings('test-select', 'default-option'),
      toggle: getSettings('test-toggle', true),
      previousScrape: previousScrape,
      hasSettingsGlobal: typeof SCRAPER_SETTINGS !== 'undefined'
    }),
    url: 'https://example.invalid/test.m3u8'
  }];
}

function onSettings() {
  return [
    {
      key: 'update-last-scrape',
      type: 'toggle',
      label: 'Update last scrape',
      description: 'Updates localStorage when this scraper runs.',
      defaultValue: true
    },
    { key: 'test-header', type: 'header', label: 'Header label' },
    { key: 'test-info', type: 'info', label: 'Information entry' },
    {
      key: 'test-text',
      type: 'text',
      label: 'Text label',
      description: 'Text description',
      defaultValue: 'Default value',
      placeholder: 'Placeholder',
      isPassword: false
    },
    {
      key: 'test-text-password',
      type: 'text',
      label: 'Password label',
      description: 'Password description',
      defaultValue: '',
      placeholder: 'Password',
      isPassword: true
    },
    {
      key: 'test-select',
      type: 'select',
      label: 'Select',
      description: 'Select description',
      options: [
        { label: 'Option 1', value: 'option-1' },
        { label: 'Option 2', value: 'option-2' },
        { label: 'Option 3', value: 'option-3' }
      ],
      defaultValue: 'option-1'
    },
    {
      key: 'test-toggle',
      type: 'toggle',
      label: 'Toggle',
      description: 'Toggle description',
      defaultValue: true
    }
  ];
}

module.exports = { getStreams, onSettings };
