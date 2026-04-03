export type ThemeColor = 'green' | 'gray' | 'cyan' | 'magenta' | 'red' | 'yellow' | 'black'

export const uiTheme = {
  brand: 'green',
  brandMuted: 'gray',
  assistantMarker: 'green',
  userMessageBackground: 'gray',
  inlineCodeBackground: 'black',
  inlineCodeForeground: 'green',
  codeFenceBackground: 'black',
  codeFenceForeground: 'green',
  promptBorder: 'gray',
  promptMarker: 'green',
  bashBorder: 'green',
  overlayBorder: 'green',
  overlayTitle: 'green',
  overlaySelection: 'green',
  warning: 'yellow',
} as const satisfies Record<string, ThemeColor>
