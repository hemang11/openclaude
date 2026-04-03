type RenderPlaceholderArgs = {
  placeholder?: string
  value: string
  showCursor?: boolean
  focus?: boolean
  terminalFocus?: boolean
  hidePlaceholderText?: boolean
}

export function renderPlaceholder({
  placeholder,
  value,
  hidePlaceholderText = false,
}: RenderPlaceholderArgs): {
  showPlaceholder: boolean
  renderedPlaceholder: string
} {
  const showPlaceholder = value.length === 0
  const fallbackPlaceholder = placeholder ?? 'Ask OpenClaude to work on the current repository'
  return {
    showPlaceholder,
    renderedPlaceholder: hidePlaceholderText ? '' : fallbackPlaceholder,
  }
}
